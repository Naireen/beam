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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.beam.sdk.metrics.Histogram;
import org.apache.beam.sdk.util.Preconditions;

/** Stores and exports metrics for a batch of Kafka Client RPCs. */
public interface KafkaMetrics {

  void updateSuccessfulRpcMetrics(String topic, Instant start, Instant end, KafkaSinkMetrics.RpcMethod method);

  void updateKafkaMetrics();

  /** No-op implementation of {@code KafkaResults}. */
  class NoOpKafkaMetrics implements KafkaMetrics {
    private NoOpKafkaMetrics() {}

    @Override
    public void updateSuccessfulRpcMetrics(String topic, Instant start, Instant end, KafkaSinkMetrics.RpcMethod method) {}

    @Override
    public void updateKafkaMetrics() {}

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

    // Needs to be a nested map of method to topic to hist
    static HashMap<String, HashMap<String, Histogram>> latencyHistograms =
        new HashMap<String, HashMap<String, Histogram>>();

    abstract HashMap<String, HashMap<String, ConcurrentLinkedQueue<Duration>>>
        perTopicRpcLatencies();

    abstract AtomicBoolean isWritable();

    public static KafkaMetricsImpl create() {

      // Create a nested initial hash map with the keys created
      HashMap<String, ConcurrentLinkedQueue<Duration>> pollLatencyList =
          new HashMap<String, ConcurrentLinkedQueue<Duration>>();
      HashMap<String, ConcurrentLinkedQueue<Duration>> backlogLatencyList =
          new HashMap<String, ConcurrentLinkedQueue<Duration>>();
      HashMap<String, HashMap<String, ConcurrentLinkedQueue<Duration>>> latencies =
          new HashMap<String, HashMap<String, ConcurrentLinkedQueue<Duration>>>();
      latencies.put(KafkaSinkMetrics.RpcMethod.POLL.toString(), pollLatencyList);
      latencies.put(KafkaSinkMetrics.RpcMethod.BACKLOG_POLL.toString(), backlogLatencyList);

      // Initialize histograms
      latencyHistograms.put(
          KafkaSinkMetrics.RpcMethod.POLL.toString(), new HashMap<String, Histogram>());
      latencyHistograms.put(
          KafkaSinkMetrics.RpcMethod.BACKLOG_POLL.toString(), new HashMap<String, Histogram>());

      return new AutoValue_KafkaMetrics_KafkaMetricsImpl(latencies, new AtomicBoolean(true));
    }

    /** Record the rpc status and latency of a successful Kafka poll RPC call. */
    @Override
    public void updateSuccessfulRpcMetrics(
        String topic, Instant start, Instant end, KafkaSinkMetrics.RpcMethod method) {
      //  KafkaSinkMetrics.RpcMethod.POLL
      if (isWritable().get()) {
        // First get can't return null.
        ConcurrentLinkedQueue<Duration> latencies = perTopicRpcLatencies().get(method).get(topic);
        if (latencies == null) {
          latencies = new ConcurrentLinkedQueue<Duration>();
          latencies.add(Duration.between(start, end));
          perTopicRpcLatencies().get(method).put(topic, latencies);
        } else {
          latencies.add(Duration.between(start, end));
        }
      }
    }

    /** Record rpc latency histogram metrics for all recorded topics. */
    private void recordRpcLatencyMetrics() {
      // iterate over twice.
      for (Map.Entry<String, HashMap<String, ConcurrentLinkedQueue<Duration>>> methodLatencies :
          perTopicRpcLatencies()) {
          String method = methodLatencies.getKey();
        for (Map.Entry<String, ConcurrentLinkedQueue<Duration>> topicLatencies :
            methodLatencies.entrySet()) {
          Histogram topicHistogram;
          if (latencyHistograms.get(method).containsKey(topicLatencies.getKey())) {
            topicHistogram = latencyHistograms.get(topicLatencies.getKey());
          } else {
            topicHistogram =
                KafkaSinkMetrics.createRPCLatencyHistogram(method, topicLatencies.getKey());
            latencyHistograms.get(method).put(topicLatencies.getKey(), topicHistogram);
          }
          // update all the latencies
          for (Duration d : topicLatencies.getValue()) {
            Preconditions.checkArgumentNotNull(topicHistogram);
            topicHistogram.update(d.toMillis());
          }
        }
      }
    }

    /**
     * Export all metrics recorded in this instance to the underlying {@code perWorkerMetrics}
     * containers. This function will only report metrics once per instance. Subsequent calls to
     * this function will no-op.
     */
    @Override
    public void updateKafkaMetrics() {
      if (!isWritable().compareAndSet(true, false)) {
        return;
      }
      recordRpcLatencyMetrics();
    }
  }
}
