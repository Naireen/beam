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

import org.apache.commons.lang3.tuple.Pair;
import org.apache.beam.sdk.metrics.DelegatingHistogram;
import org.apache.beam.sdk.metrics.DelegatingGauge;
import org.apache.beam.sdk.metrics.Histogram;
import org.apache.beam.sdk.metrics.Gauge;
import org.apache.beam.sdk.metrics.LabeledMetricNameUtils;
import org.apache.beam.sdk.metrics.MetricName;
import org.apache.beam.sdk.util.HistogramData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.lang3.tuple.ImmutablePair;

/**
 * Helper class to create per worker metrics for Kafka Sink stages.
 *
 * <p>Metrics will be in the namespace 'KafkaSink' and have their name formatted as:
 *
 * <p>'{baseName}-{metricLabelKey1}:{metricLabelVal1};...{metricLabelKeyN}:{metricLabelValN};' ????
 */

// TODO, refactor out common parts for BQ sink, so it can be reused with other sinks, eg, GCS?
// @SuppressWarnings("unused")
public class KafkaSinkMetrics {
  private static boolean supportKafkaMetrics = true;

  public static final String METRICS_NAMESPACE = "KafkaSink";

  private static final Logger LOG = LoggerFactory.getLogger(KafkaSinkMetrics.class);

  // Base Metric names
  private static final String RPC_LATENCY = "RpcLatency";

  // Kafka Consumer Method names
  enum RpcMethod {
    POLL,
  }

  // Metric labels
  private static final String TOPIC_LABEL = "topic_name";
  private static final String RPC_METHOD = "rpc_method";
  private static final String PARTITION = "partition"; // use a mock backlog numner

  private static MetricName createRPCMetricName(RpcMethod method, String topic) {
    LabeledMetricNameUtils.MetricNameBuilder nameBuilder =
        LabeledMetricNameUtils.MetricNameBuilder.baseNameBuilder(RPC_LATENCY);
    nameBuilder.addLabel(RPC_METHOD, method.toString());
    nameBuilder.addLabel(TOPIC_LABEL, topic);
    return nameBuilder.build(METRICS_NAMESPACE);
  }

  private static MetricName createBacklogMetricName(Integer partition, String topic) {
    LabeledMetricNameUtils.MetricNameBuilder nameBuilder =
        LabeledMetricNameUtils.MetricNameBuilder.baseNameBuilder(RPC_LATENCY);
    nameBuilder.addLabel(PARTITION, partition.toString());
    nameBuilder.addLabel(TOPIC_LABEL, topic);
    return nameBuilder.build(METRICS_NAMESPACE);
  }

  /**
   * Creates an Histogram metric to record RPC latency. Metric will have name.
   *
   * <p>'RpcLatency*rpc_method:{method};topic_name:{topic};'
   *
   * @param method Kafka method associated with this metric.
   * @param topic Kafka topic associated with this metric.
   * @return Histogram with exponential buckets with a sqrt(2) growth factor.
   */
  public static Histogram createRPCLatencyHistogram(
      RpcMethod method, String topic, boolean processWideContainer) {
    MetricName metricName = createRPCMetricName(method, topic);
    HistogramData.BucketType buckets = HistogramData.ExponentialBuckets.of(1, 17);
    LOG.info("xxx create histogram metrics  " + metricName.getName());
    return new DelegatingHistogram(metricName, buckets, processWideContainer, true);
  }

  public static Histogram createRPCLatencyHistogram(RpcMethod method, String topic) {
    MetricName metricName = createRPCMetricName(method, topic);
    HistogramData.BucketType buckets = HistogramData.ExponentialBuckets.of(1, 17);
    LOG.info("xxx create histogram metrics in current container" + metricName.getName());
    return new DelegatingHistogram(metricName, buckets, false, true);
  }

  public static Gauge createBacklogGauge(Integer partition, String topic,  boolean processWideContainer) {
    MetricName metricName = createBacklogMetricName(partition, topic);
    LOG.info("xxx create gauge metrics not in current container" + metricName.getName());
    return new DelegatingGauge(metricName, false, processWideContainer);
  }

  /**
   * Returns a container to store metrics for Kafka metrics in Unbounded Readed. If these metrics
   * are disabled, then we return a no-op container.
   */
  static KafkaMetrics kafkaMetrics() {
    if (supportKafkaMetrics) {
      return KafkaMetrics.KafkaMetricsImpl.create();
    } else {
      return KafkaMetrics.NoOpKafkaMetrics.getInstance();
    }
  }

  public static void setSupportKafkaMetrics(boolean supportKafkaMetrics) {
    KafkaSinkMetrics.supportKafkaMetrics = supportKafkaMetrics;
  }
}
