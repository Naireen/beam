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
package org.apache.beam.sdk.metrics;

import java.io.Serializable;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.metrics.Metrics.MetricsFlag;

/** Implementation of {@link Counter} that delegates to the instance for the current context. */
@Internal
public class DelegatingCounter implements Metric, Counter, Serializable {
  private final MetricName name;
  private final boolean processWideContainer;

  /**
   * Create a {@code DelegatingCounter} and {@code processWideContainer} set to false.
   *
   * @param name Metric name for this metric.
   */
  public DelegatingCounter(MetricName name) {
    this(name, false);
  }

  /**
   * @param name Metric name for this metric.
   * @param processWideContainer Whether this Counter is stored in the ProcessWide container or the
   *     current thread's container.
   */
  public DelegatingCounter(MetricName name, boolean processWideContainer) {
    this.name = name;
    this.processWideContainer = processWideContainer;
  }

  /** Increment the counter. */
  @Override
  public void inc() {
    inc(1);
  }

  /** Increment the counter by the given amount. */
  @Override
  public void inc(long n) {
    if (MetricsFlag.counterDisabled()) {
      return;
    }
    MetricsContainer container =
        this.processWideContainer
            ? MetricsEnvironment.getProcessWideContainer()
            : MetricsEnvironment.getCurrentContainer();
    if (container == null) {
      return;
    }
    container.getCounter(name).inc(n);
  }

  /* Decrement the counter. */
  @Override
  public void dec() {
    inc(-1);
  }

  /* Decrement the counter by the given amount. */
  @Override
  public void dec(long n) {
    inc(-1 * n);
  }

  @Override
  public MetricName getName() {
    return name;
  }
}
