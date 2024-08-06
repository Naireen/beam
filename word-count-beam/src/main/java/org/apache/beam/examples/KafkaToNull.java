/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

 package org.apache.beam.examples;

 import java.util.stream.Collectors;
 import java.util.stream.IntStream;
 import org.apache.beam.sdk.Pipeline;
 import org.apache.beam.sdk.io.kafka.KafkaIO;
 import org.apache.beam.sdk.options.Default;
 import org.apache.beam.sdk.options.Description;
 import org.apache.beam.sdk.options.PipelineOptions;
 import org.apache.beam.sdk.options.PipelineOptionsFactory;
 import org.apache.beam.sdk.transforms.DoFn;
 import org.apache.beam.sdk.transforms.ParDo;
 import org.apache.beam.sdk.values.KV;
 import org.apache.beam.sdk.values.PCollection;
 import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
 import org.apache.kafka.common.TopicPartition;
 import org.apache.kafka.common.serialization.ByteArrayDeserializer;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.apache.beam.sdk.options.Default;
 import org.apache.beam.sdk.transforms.Reshuffle;
 import org.apache.kafka.clients.consumer.ConsumerConfig;

 /** Reads from Kafka and performs some operations then drops. */
 public class KafkaToNull {
   /** Inherits standard configuration options. */
   public interface KafkaToNullOptions extends PipelineOptions {
     @Description("Input Kafka topics")
     @Default.String("test-topic")
     String getInputTopics();
 
     void setInputTopics(String value);
 
     @Description("Bootstrap server(s) for Kafka")
     @Default.String("nokill-naireenhussain-kafka-test-m.us-central1-a:9092")
     String getKafkaBootstrapServers();
 
     void setKafkaBootstrapServers(String value);
 
     @Description(
         "Comma-separated list of numbers of partitions per topic, the order must correspond to"
             + " topics in the inputTopics list")
     String getNumPartitions();
 
     void setNumPartitions(String value);
 
     @Description("Job name prefix to save the results under. Name will be appended with timestamp.")
     @Default.String("Kafka-Null")
     String getPipelineName();
 
     void setPipelineName(String value);
   }
 
   /**
    * Sets up and starts streaming pipeline.
    *
    * <p>This pipeline listens on the specified Kafka topic and reads all records.
    */
   public static void main(String[] args) {
 
     KafkaToNullOptions options =
         PipelineOptionsFactory.fromArgs(args).withValidation().as(KafkaToNullOptions.class);
     final Logger logger = LoggerFactory.getLogger(KafkaToNull.class);
 
     // Run processing pipeline
     options.setJobName(options.getPipelineName() + System.currentTimeMillis());
     Pipeline pipeline = Pipeline.create(options);
     final String[] topics = options.getInputTopics().split(",");
     final String[] partitions = options.getNumPartitions().split(",");
     String pipelineName = options.getPipelineName();
 
     if (topics.length != partitions.length) {
       logger.error("Specify number of partitions for each topic in the list");
       return;
     }
     for (int index = 0; index < topics.length; index++) {
       final String topic = topics[index];
       KafkaIO.Read<byte[], byte[]> read =
           KafkaIO.<byte[], byte[]>read()
               .withBootstrapServers(options.getKafkaBootstrapServers())
              //  .withConsumerConfigUpdates(new ImmutableMap.Builder<String, Object>()
              //     .put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true")
              //     .put(ConsumerConfig.GROUP_ID_CONFIG, "group_id")
              //     .build())
               .withKeyDeserializer(ByteArrayDeserializer.class)
               .withValueDeserializer(ByteArrayDeserializer.class)
               .withTopicPartitions(
                   IntStream.range(0, Integer.parseInt(partitions[index]))
                       .mapToObj(i -> new TopicPartition(topic, i))
                       .collect(Collectors.toList()));
 
       PCollection<KV<byte[], byte[]>> messages = pipeline.apply(read.withoutMetadata());
        /// put this in a if block
      //  messages =  messages.apply(
      //      "Reshuffle",
      //      Reshuffle.<KV<byte[], byte[]>>viaRandomKey()
      //          .withNumBuckets(20));

       messages.apply(
           "to string",
           ParDo.of(
               new DoFn<KV<byte[], byte[]>, String>() {
                 @ProcessElement
                 public void processElement(ProcessContext c) {
                   c.output(c.element().getValue().toString());
                 }
               }));
     }
     pipeline.run();
   }
 }
 