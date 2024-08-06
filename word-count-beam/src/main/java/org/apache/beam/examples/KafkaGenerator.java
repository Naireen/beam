/*
 * Copyright (C) 2017 Google Inc.
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
 import org.apache.beam.sdk.options.Default;
 import java.nio.ByteBuffer;
 import org.apache.beam.sdk.Pipeline;
 import org.apache.beam.sdk.io.GenerateSequence;
 import org.apache.beam.sdk.io.kafka.KafkaIO;
 import org.apache.beam.sdk.options.Description;
 import org.apache.beam.sdk.options.PipelineOptionsFactory;
 import org.apache.beam.sdk.options.Validation;
 import org.apache.beam.sdk.transforms.MapElements;
 import org.apache.beam.sdk.transforms.SimpleFunction;
 import org.apache.beam.sdk.values.KV;
 import org.apache.beam.sdk.values.PCollection;
 import org.apache.kafka.common.serialization.ByteArraySerializer;
 import org.joda.time.Duration;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.apache.beam.sdk.options.PipelineOptions;
 
 /** Generator pipeline which outputs Kafka messages. */
 public class KafkaGenerator {
   /** Inherits standard configuration options. */
   public interface KafkaGeneratorOptions extends PipelineOptions {
     @Description("Comma-separated list of output Kafka topics")
     @Default.String("test-topic")
     String getOutputTopics();

     void setOutputTopics(String value);
 
     @Description("Bootstrap server(s) for Kafka")
     @Default.String("nokill-naireenhussain-kafka-test-m.us-central1-a:9092")
     String getKafkaBootstrapServers();

     void setKafkaBootstrapServers(String value);

     @Description("Rate")
     @Default.Integer(5)
     Integer getInputRate();
 
     void setInputRate(Integer rate);
   }
 
   private static final Logger logger = LoggerFactory.getLogger(KafkaGenerator.class);
 
   /** Sets up and starts generator pipeline. */
   public static void main(String[] args) {
     KafkaGeneratorOptions options =
         PipelineOptionsFactory.fromArgs(args).withValidation().as(KafkaGeneratorOptions.class);
 
     int sizeBytes = 1000;
     int rate = options.getInputRate();
 
     // Input Validation
     if (rate == 0) {
       throw new IllegalArgumentException("Generator rate can not be zero.");
     }
 
     Pipeline generator = Pipeline.create(options);
     PCollection<Long> input =
         generator.apply(
             "Generate Numbers",
             GenerateSequence.from(0L)
                 .withRate(rate, Duration.standardSeconds(1L)));
 
     PCollection<KV<byte[], byte[]>> inputMessages =
         input.apply(
             MapElements.via(
                 new SimpleFunction<Long, KV<byte[], byte[]>>() {
                   public KV<byte[], byte[]> apply(Long value) {
                     ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
                     buffer.putLong(value);
                     return KV.of(buffer.array(), new byte[sizeBytes]);
                   }
                 }));
     for (String topic : options.getOutputTopics().split(",")) {
       inputMessages.apply(
           "Write",
           KafkaIO.<byte[], byte[]>write()
               .withBootstrapServers(options.getKafkaBootstrapServers())
               .withTopic(topic)
               .withKeySerializer(ByteArraySerializer.class)
               .withValueSerializer(ByteArraySerializer.class));
     }
     generator.run();
   }
 }
 