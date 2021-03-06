/*
 * Copyright 2015 BlackBerry Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.blackberry.bdp.kaboom;

import com.blackberry.bdp.common.jmx.MetricRegistrySingleton;
import com.blackberry.bdp.common.props.Parser;
import com.blackberry.bdp.kaboom.api.KaBoomClient;
import com.codahale.metrics.Meter;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.hadoop.fs.FileSystem;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

public class KaBoom {

	private static final Logger LOG = LoggerFactory.getLogger(KaBoom.class);
	private static final Charset UTF8 = Charset.forName("UTF-8");
	boolean shutdown = false;
	private StartupConfig config;
	private KaBoomClient client;
	private final static Object serverLock = new Object();
	private Meter deadWorkerMeter;
	private Meter gracefulWorkerShutdownMeter;

	public static void main(String[] args) throws Exception {

		MetricRegistrySingleton.getInstance().enableJmx();

		LOG.info("*******************************************");
		LOG.info("***         KABOOM SERVER START         ***");
		LOG.info("*******************************************");

		new KaBoom().run();
	}

	public KaBoom() throws Exception {
	}

	private void run() throws Exception {
		if (Boolean.parseBoolean(System.getProperty("metrics.to.console", "false").trim())) {
			MetricRegistrySingleton.getInstance().enableConsole();
		}

		deadWorkerMeter = MetricRegistrySingleton.getInstance().
			 getMetricsRegistry().meter("kaboom:total:dead workers");

		gracefulWorkerShutdownMeter = MetricRegistrySingleton.getInstance().
			 getMetricsRegistry().meter("kaboom:total:gracefully shutdown workers");

		try {
			Properties props = StartupConfig.getProperties();
			Parser propsParser = new Parser(props);

			if (propsParser.parseBoolean("configuration.authority.zk", false)) {
				// TODO: ZK
			} else {
				LOG.info("Configuration authority is file based");
				config = new StartupConfig(props);
			}

			config.logConfiguraton();
		} catch (Exception e) {
			LOG.error("an error occured while building configuration object: ", e);
			throw e;
		}

		// Ensure that the required zk paths exist
		for (String path : new String[]{config.getZkPathLeaderClientId(),
			config.getZkRootPathClients(),
			config.getZkRootPathPartitionAssignments(),
			config.getZkRootPathFlagAssignments()}) {
			if (config.getKaBoomCurator().checkExists().forPath(path) == null) {
				try {
					LOG.warn("the path {} was not found in ZK and needs to be created", path);
					config.getKaBoomCurator().create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(path);
					LOG.warn("path {} created in ZK", path);
				} catch (Exception e) {
					LOG.error("Error creating ZooKeeper node {} ", path, e);
				}
			} else {
				LOG.info("required path {} already exists in zookeeper", path);
			}
		}

		// Register our existence
		{
			client = new KaBoomClient(config.getKaBoomCurator(),
				 String.format("%s/%s", config.getZkRootPathClients(), config.getKaboomId()));
			client.setId(config.getKaboomId());
			client.setMode(CreateMode.EPHEMERAL);
			client.setHostname(config.getHostname());
			client.setWeight(config.getWeight());
			client.save();
		}

		// Instantiate our load balancer
		Leader loadBalancer = null;
		if (config.getLoadBalancerType().equals("even")) {
			loadBalancer = new EvenLoadBalancer(config);
		} else {
			if (config.getLoadBalancerType().equals("local")) {
				loadBalancer = new LocalLoadBalancer(config);
			}
		}

		// Start leader election thread
		final LeaderSelector leaderSelector = new LeaderSelector(config.getKaBoomCurator(),
			 config.getZkPathLeaderClientId(), loadBalancer);
		leaderSelector.autoRequeue();
		leaderSelector.start();

		final Map<String, Worker> partitionToWorkerMap = new HashMap<>();
		final Map<String, Thread> partitionToThreadsMap = new HashMap<>();

		{
			Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
				@Override
				public void run() {
					// Request shutdown and wait for the lock
					shutdown();
					synchronized (serverLock) {
						// Stop all workers
						Iterator<Map.Entry<String, Worker>> workers = partitionToWorkerMap.entrySet().iterator();
						while (workers.hasNext()) {
							Map.Entry<String, Worker> entry = workers.next();
							entry.getValue().stop();
						}

						// Wait for threads to finish
						Iterator<Map.Entry<String, Thread>> threads = partitionToThreadsMap.entrySet().iterator();
						while (threads.hasNext()) {
							Map.Entry<String, Thread> entry = threads.next();
							try {
								entry.getValue().join();
							} catch (InterruptedException ie) {
								LOG.error("Interrupted waiting for thread to finish", ie);
							}
						}

						try {
							FileSystem.get(config.getHadoopConfiguration()).close();
						} catch (Throwable t) {
							LOG.error("Error closing Hadoop filesystem", t);
						}
						try {
							config.getKaBoomCurator().delete().forPath("/kaboom/clients/" + config.getKaboomId());
						} catch (Exception e) {
							LOG.error("Error deleting /kaboom/clients/{}", config.getKaboomId(), e);
						}
						leaderSelector.close();
						config.getKaBoomCurator().close();
					}
				}
			}));
		}

		Pattern topicPartitionPattern = Pattern.compile("^(.*)-(\\d+)$");

		while (shutdown == false) {
			synchronized (serverLock) {
				// Get all my assignments and create a worker if there's anything not already being worked
				Map<String, Boolean> validWorkingPartitions = new HashMap<>();
				for (String partitionId : client.getAssignments(config.getKaBoomCurator(), config.getZkRootPathPartitionAssignments())) {
					if (partitionToWorkerMap.containsKey(partitionId)) {
						if (false == partitionToThreadsMap.get(partitionId).isAlive()) {
							if (false == partitionToWorkerMap.get(partitionId).isAborting()) {
								LOG.info("worker thead for {} found to have been shutdown gracefully", partitionId);
								gracefulWorkerShutdownMeter.mark();
							} else {
								LOG.error("worker thead for {} found dead (removed thread/worker objects)", partitionId);
								deadWorkerMeter.mark();
							}
							validWorkingPartitions.remove(partitionId);
							partitionToWorkerMap.remove(partitionId);
							partitionToThreadsMap.remove(partitionId);
						} else {
							validWorkingPartitions.put(partitionId, true);
						}
					} else {
						LOG.info("KaBoom clientId {} assigned to partitonId {} and a worker doesn't exist", config.getKaboomId(), partitionId);
						Matcher m = topicPartitionPattern.matcher(partitionId);
						if (m.matches()) {
							String topic = m.group(1);
							int partition = Integer.parseInt(m.group(2));
							try {
								Worker worker = new Worker(config, topic, partition);
								partitionToWorkerMap.put(partitionId, worker);
								partitionToThreadsMap.put(partitionId, new Thread(worker));
								partitionToThreadsMap.get(partitionId).start();
								LOG.info("KaBoom clientId {} assigned to partitonId {} and a new worker has been started",
									 config.getKaboomId(), partitionId);
								validWorkingPartitions.put(partitionId, true);
							} catch (Exception e) {
								LOG.error("failed to create new worker for {}-{}", topic, partition, e);
							}
						} else {
							LOG.error("Could not get topic and partition from node name. ({})", partitionId);
						}
					}
				}

				Iterator<Map.Entry<String, Worker>> iter = partitionToWorkerMap.entrySet().iterator();
				while (iter.hasNext()) {
					Map.Entry<String, Worker> entry = iter.next();
					Worker worker = entry.getValue();
					if (!validWorkingPartitions.containsKey(worker.getPartitionId())) {
						worker.stop();
						LOG.info("Worker currently assigned to {} is no longer valid has been instructed to stop working", worker.getPartitionId());
					}
					if (worker.pinged()) {
						if (!worker.getPong()) {
							LOG.error("[{}] has not responded from being pinged, aborting", worker.getPartitionId());
							synchronized(worker.getZkLock()) {
								worker.abort();
								partitionToThreadsMap.get(worker.getPartitionId()).interrupt();
								iter.remove();
							}
						} else {
							worker.ping();
						}
					} else {
						worker.ping();
					}
				}
			}
			Thread.sleep(config.getRunningConfig().getKaboomServerSleepDurationMs());
		}
	}

	public void shutdown() {
		shutdown = true;
	}

}
