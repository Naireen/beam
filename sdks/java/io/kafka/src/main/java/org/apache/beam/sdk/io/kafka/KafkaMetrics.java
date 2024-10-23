/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.io.kafka;

import com.google.auto.value.AutoValue;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.beam.sdk.metrics.Gauge;
import org.apache.beam.sdk.metrics.Histogram;
import org.apache.beam.sdk.util.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stores and exports metrics for a batch of Kafka Client RPCs. */
public interface KafkaMetrics {

  void updateSuccessfulRpcMetrics(String topic, Duration elapsedTime);

  void recordRpcLatencyMetric(String topic, Duration duration);

  void updatePartitionBacklogStats(Integer partition, String topic, long backlog);

  void updateKafkaMetrics();

  void recordPartitionBacklog(Integer partition, String topic, long backlog);

  /** No-op implementation of {@code KafkaResults}. */
  class NoOpKafkaMetrics implements KafkaMetrics {
    private NoOpKafkaMetrics() {}

    @Override
    public void updateSuccessfulRpcMetrics(String topic, Duration elapsedTime) {}

    @Override
    public void recordRpcLatencyMetric(String topic, Duration elapsedTime) {}

    @Override
    public void updateKafkaMetrics() {}

    @Override
    public void updatePartitionBacklogStats(Integer partition, String topic, long backlog) {}

    @Override
    public void recordPartitionBacklog(Integer partition, String topic, long backlog) {}

    private static NoOpKafkaMetrics singleton = new NoOpKafkaMetrics();

    static NoOpKafkaMetrics getInstance() {
      return singleton;
    }
  }

  /**
   * Metrics of a batch of RPCs. Member variables are thread safe; however, this class does not have
   * atomicity across member variables.
   *
   * <p>Expected usage: A number of threads record metrics in an instance of this class with the
   * member methods. Afterwards, a single thread should call {@code updateStreamingInsertsMetrics}
   * which will export all counters metrics and RPC latency distribution metrics to the underlying
   * {@code perWorkerMetrics} container. Afterwards, metrics should not be written/read from this
   * object.
   */
  @AutoValue
  abstract class KafkaMetricsImpl implements KafkaMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaMetricsImpl.class);

    static HashMap<String, Histogram> latencyHistograms = new HashMap<String, Histogram>();

    // Keyed by topic and partition
    // Pair<Integer, String> simplePair = new Pair<>(42, "Second");
    // create gauge in correct thread only, otherwise just store longs
    static HashMap<ImmutablePair<Integer, String>, Long> partitionBacklogs = new HashMap<ImmutablePair<Integer, String>, Long>();
    static HashMap<ImmutablePair<Integer, String>, Gauge> partitionBacklogGauges = new HashMap<ImmutablePair<Integer, String>, Gauge>();


    abstract HashMap<String, ConcurrentLinkedQueue<Duration>> perTopicRpcLatencies();

    abstract AtomicBoolean isWritable();

    public static KafkaMetricsImpl create() {
      return new AutoValue_KafkaMetrics_KafkaMetricsImpl(
          new HashMap<String, ConcurrentLinkedQueue<Duration>>(), new AtomicBoolean(true));
    }

    // private static final Logger LOG = LoggerFactory.getLogger(KafkaMetricsImpl.class);

    /** Record the rpc status and latency of a successful Kafka poll RPC call. */
    @Override
    public void updateSuccessfulRpcMetrics(String topic, Duration elapsedTime) {
      if (isWritable().get()) {
        ConcurrentLinkedQueue<Duration> latencies = perTopicRpcLatencies().get(topic);
        if (latencies == null) {
          latencies = new ConcurrentLinkedQueue<Duration>();
          latencies.add(elapsedTime);
          perTopicRpcLatencies().put(topic, latencies);
        } else {
          latencies.add(elapsedTime);
        }
      }
    }

    @Override
    public void updatePartitionBacklogStats(Integer partition, String topic, long backlog) {
      // Gauge partitionBacklog;
      if (isWritable().get()) {
        ImmutablePair<Integer, String> simplePair = new ImmutablePair<>(partition, topic);
        // always overwrite old value;
        partitionBacklogs.put(simplePair, backlog);
        LOG.info("xxx {}, {}", partition, topic);
              // KafkaSinkMetrics.createBacklogGauge(
              //   partition, topic, /*processWideContainer*/ false); // should this be false?
              //   partitionBacklogs.put(topic, partitionBacklog);
        LOG.info("xxx update backlog value {}", backlog);
     }
    }

    /** Record rpc latency histogram metrics for all recorded topics. */
    private void recordRpcLatencyMetrics() {
      for (Map.Entry<String, ConcurrentLinkedQueue<Duration>> topicLatencies :
          perTopicRpcLatencies().entrySet()) {
        Histogram topicHistogram;
        if (latencyHistograms.containsKey(topicLatencies.getKey())) {
          topicHistogram = latencyHistograms.get(topicLatencies.getKey());
        } else {
          topicHistogram =
              KafkaSinkMetrics.createRPCLatencyHistogram(
                  KafkaSinkMetrics.RpcMethod.POLL, topicLatencies.getKey());
          latencyHistograms.put(topicLatencies.getKey(), topicHistogram);
        }
        for (Duration d : topicLatencies.getValue()) {
          Preconditions.checkArgumentNotNull(topicHistogram);
          topicHistogram.update(d.toMillis());
        }
      }
    }

    /** Record rpc latency for a singlar topic on the thread */
    @Override
    public void recordRpcLatencyMetric(String topic, Duration duration) {
      Histogram topicHistogram;
      if (latencyHistograms.containsKey(topic)) {
        topicHistogram = latencyHistograms.get(topic);
      } else {
        topicHistogram =
            KafkaSinkMetrics.createRPCLatencyHistogram(
                KafkaSinkMetrics.RpcMethod.POLL, topic, /*processWideContainer*/ false); // was showing when false
        latencyHistograms.put(topic, topicHistogram);
      }
      topicHistogram.update(duration.toMillis());
    }

   @Override
   public void recordPartitionBacklog(Integer partition, String topic, long backlog) {
      LOG.info("xxx create backlog gauge counter");
      ImmutablePair<Integer, String> simplePair = new ImmutablePair<>(partition, topic);
      // partitionBacklogs.put(simplePair, backlog);
        Gauge partitionBacklogGauge;
        if (partitionBacklogGauges.containsKey(simplePair)) {
          partitionBacklogGauge = partitionBacklogGauges.get(simplePair);
        } else {
          partitionBacklogGauge =
          KafkaSinkMetrics.createBacklogGauge(
            partition, topic, /*processWideContainer*/ false); // should this be false?
        }
        partitionBacklogGauge.set(backlog);
        LOG.info("xxx gauge {} {}", partitionBacklogGauge, backlog);
    }

    /**
     * Export all metrics recorded in this instance to the underlying {@code perWorkerMetrics}
     * containers. This function will only report metrics once per instance. Subsequent calls to
     * this function will no-op.
     */
    @Override
    public void updateKafkaMetrics() {
      if (!isWritable().compareAndSet(true, false)) {
        LOG.warn("Updating stale Kafka metrics container");
        return;
      }
      LOG.info("xxx start recording metrics");
      recordRpcLatencyMetrics();
      // recordPartitionBacklog();
    }
  }
}
