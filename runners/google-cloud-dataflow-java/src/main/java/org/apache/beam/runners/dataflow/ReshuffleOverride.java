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
package org.apache.beam.runners.dataflow;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.beam.runners.core.construction.PTransformReplacements;
import org.apache.beam.runners.core.construction.ReplacementOutputs;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.runners.AppliedPTransform;
import org.apache.beam.sdk.runners.PTransformOverrideFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.Reshuffle;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.util.ShardedKey;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TupleTag;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.Lists;
import org.apache.beam.sdk.values.KV;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.ReshuffleTrigger;
import org.apache.beam.sdk.transforms.windowing.TimestampCombiner;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.util.IdentityWindowFn;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.sdk.values.WindowingStrategy;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.primitives.UnsignedInteger;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Duration;
import java.nio.ByteBuffer;
import java.util.UUID;
import org.apache.beam.sdk.values.PCollection;

@SuppressWarnings({
  "rawtypes" // TODO(https://github.com/apache/beam/issues/20447)
})
public class ReshuffleOverride {

  // Override is only implemented for streaming pipelines for only the autosharded reshuffle method
 
  static class StreamingReshuffleWithRunnerDeterminedBucketsOverrideFactory<T, K, V>
      implements PTransformOverrideFactory<
          PCollection<T>,
          PCollection<T>,
          Reshuffle<K,V>.WithRunnerDeterminedBuckets> {

    private final DataflowRunner runner;

    StreamingReshuffleWithRunnerDeterminedBucketsOverrideFactory(DataflowRunner runner) {
      this.runner = runner;
    }

    @Override
    public PTransformReplacement<PCollection<V>, PCollection<V>>
        getReplacementTransform(
            AppliedPTransform<
                    PCollection<T>,
                    PCollection<T>,
                    Reshuffle<K,V>.WithRunnerDeterminedBuckets>
                transform) {
      return PTransformReplacement.of(
          PTransformReplacements.getSingletonMainInput(transform),
          new StreamingReshuffleWithRunnerDeterminedBuckets<>(
              runner,
              transform.getTransform(),
              PTransformReplacements.getSingletonMainOutput(transform)));
    }

    @Override
    public Map<PCollection<?>, ReplacementOutput> mapOutputs(
        Map<TupleTag<?>, PCollection<?>> outputs,
        PCollection<V> newOutput) {
      return ReplacementOutputs.singleton(outputs, newOutput);
    }
  }

  /**
   * Specialized implementation of {@link Reshuffle.withRunnerDeterminedBuckets} for unbounded Dataflow
   * pipelines. The override does the same thing as the original transform but additionally records
   * the output in order to append required step properties during the graph translation.
   */
  static class StreamingReshuffleWithRunnerDeterminedBuckets<T, K, V>
      extends PTransform<PCollection<V>, PCollection<V>> {

    private final transient DataflowRunner runner;
    private final Reshuffle<K, V>.WithRunnerDeterminedBuckets originalTransform;
    private final transient PCollection<V> originalOutput;

    public StreamingReshuffleWithRunnerDeterminedBuckets(
        DataflowRunner runner,
        Reshuffle<K, V>.WithRunnerDeterminedBuckets original,
        PCollection<V> output) {
      this.runner = runner;
      this.originalTransform = original;
      this.originalOutput = output;
    }

    @Override
    public PCollection<V> expand(PCollection<V> input) {
      // Record the output PCollection of the original transform since the new output will be
      // replaced by the original one when the replacement transform is wired to other nodes in the
      // graph, although the old and the new outputs are effectively the same.
      runner.maybeRecordPCollectionWithAutoSharding(originalOutput);
      return input.apply(originalTransform);
    }
  }
}
    