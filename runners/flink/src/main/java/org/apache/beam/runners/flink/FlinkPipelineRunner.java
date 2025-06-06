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
package org.apache.beam.runners.flink;

import static org.apache.beam.runners.fnexecution.translation.PipelineTranslatorUtils.hasUnboundedPCollections;
import static org.apache.beam.sdk.util.construction.resources.PipelineResources.detectClassPathResourcesToStage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.beam.model.jobmanagement.v1.ArtifactApi;
import org.apache.beam.model.pipeline.v1.RunnerApi;
import org.apache.beam.model.pipeline.v1.RunnerApi.Pipeline;
import org.apache.beam.runners.core.metrics.MetricsPusher;
import org.apache.beam.runners.fnexecution.provisioning.JobInfo;
import org.apache.beam.runners.jobsubmission.PortablePipelineJarUtils;
import org.apache.beam.runners.jobsubmission.PortablePipelineResult;
import org.apache.beam.runners.jobsubmission.PortablePipelineRunner;
import org.apache.beam.sdk.io.FileSystems;
import org.apache.beam.sdk.metrics.MetricsEnvironment;
import org.apache.beam.sdk.metrics.MetricsOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.SdkHarnessOptions;
import org.apache.beam.sdk.util.construction.PipelineOptionsTranslation;
import org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.Struct;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions;
import org.apache.flink.api.common.JobExecutionResult;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs a Pipeline on Flink via {@link FlinkRunner}. */
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class FlinkPipelineRunner implements PortablePipelineRunner {
  private static final Logger LOG = LoggerFactory.getLogger(FlinkPipelineRunner.class);

  private final FlinkPipelineOptions pipelineOptions;
  private final String confDir;
  private final List<String> filesToStage;

  /**
   * Setup a flink pipeline runner.
   *
   * @param pipelineOptions pipeline options configuring the flink pipeline runner.
   * @param confDir flink configuration directory. Note that pipeline option's flinkConfDir, If not
   *     null, takes precedence against this parameter.
   * @param filesToStage a list of file names to stage.
   */
  public FlinkPipelineRunner(
      FlinkPipelineOptions pipelineOptions, @Nullable String confDir, List<String> filesToStage) {
    this.pipelineOptions = pipelineOptions;
    // pipelineOptions.getFlinkConfDir takes precedence than confDir
    this.confDir =
        pipelineOptions.getFlinkConfDir() != null ? pipelineOptions.getFlinkConfDir() : confDir;
    this.filesToStage = filesToStage;
  }

  @Override
  public PortablePipelineResult run(final Pipeline pipeline, JobInfo jobInfo) throws Exception {
    MetricsEnvironment.setMetricsSupported(false);

    // Apply log levels settings at the beginning of pipeline run
    SdkHarnessOptions.getConfiguredLoggerFromOptions(pipelineOptions.as(SdkHarnessOptions.class));

    FlinkPortablePipelineTranslator<?> translator;
    if (!pipelineOptions.isStreaming() && !hasUnboundedPCollections(pipeline)) {
      // TODO: Do we need to inspect for unbounded sources before fusing?
      translator = FlinkBatchPortablePipelineTranslator.createTranslator();
    } else {
      translator = new FlinkStreamingPortablePipelineTranslator();
    }
    return runPipelineWithTranslator(pipeline, jobInfo, translator);
  }

  private <T extends FlinkPortablePipelineTranslator.TranslationContext>
      PortablePipelineResult runPipelineWithTranslator(
          final Pipeline pipeline, JobInfo jobInfo, FlinkPortablePipelineTranslator<T> translator)
          throws Exception {
    LOG.info("Translating pipeline to Flink program.");

    FlinkPortablePipelineTranslator.Executor executor =
        translator.translate(
            translator.createTranslationContext(jobInfo, pipelineOptions, confDir, filesToStage),
            translator.prepareForTranslation(pipeline));
    final JobExecutionResult result = executor.execute(pipelineOptions.getJobName());

    return createPortablePipelineResult(result, pipelineOptions);
  }

  private PortablePipelineResult createPortablePipelineResult(
      JobExecutionResult result, PipelineOptions options) {
    String resultClassName = result.getClass().getCanonicalName();
    if (resultClassName.equals("org.apache.flink.core.execution.DetachedJobExecutionResult")) {
      LOG.info("Pipeline submitted in Detached mode");
      // no metricsPusher because metrics are not supported in detached mode
      return new FlinkPortableRunnerResult.Detached();
    } else {
      LOG.info("Execution finished in {} msecs", result.getNetRuntime());
      Map<String, Object> accumulators = result.getAllAccumulatorResults();
      if (accumulators != null && !accumulators.isEmpty()) {
        LOG.info("Final accumulator values:");
        for (Map.Entry<String, Object> entry : result.getAllAccumulatorResults().entrySet()) {
          LOG.info("{} : {}", entry.getKey(), entry.getValue());
        }
      }
      FlinkPortableRunnerResult flinkRunnerResult =
          new FlinkPortableRunnerResult(accumulators, result.getNetRuntime());
      MetricsPusher metricsPusher =
          new MetricsPusher(
              flinkRunnerResult.getMetricsContainerStepMap(),
              options.as(MetricsOptions.class),
              flinkRunnerResult);
      metricsPusher.start();
      return flinkRunnerResult;
    }
  }

  /**
   * Main method to be called only as the entry point to an executable jar with structure as defined
   * in {@link PortablePipelineJarUtils}.
   */
  public static void main(String[] args) throws Exception {
    // Register standard file systems.
    FileSystems.setDefaultPipelineOptions(PipelineOptionsFactory.create());

    FlinkPipelineRunnerConfiguration configuration = parseArgs(args);
    String baseJobName =
        configuration.baseJobName == null
            ? PortablePipelineJarUtils.getDefaultJobName()
            : configuration.baseJobName;
    Preconditions.checkArgument(
        baseJobName != null,
        "No default job name found. Job name must be set using --base-job-name.");
    Pipeline pipeline = PortablePipelineJarUtils.getPipelineFromClasspath(baseJobName);
    Struct originalOptions = PortablePipelineJarUtils.getPipelineOptionsFromClasspath(baseJobName);

    // The retrieval token is only required by the legacy artifact service, which the Flink runner
    // no longer uses.
    String retrievalToken =
        ArtifactApi.CommitManifestResponse.Constants.NO_ARTIFACTS_STAGED_TOKEN
            .getValueDescriptor()
            .getOptions()
            .getExtension(RunnerApi.beamConstant);

    FlinkPipelineOptions flinkOptions =
        PipelineOptionsTranslation.fromProto(originalOptions).as(FlinkPipelineOptions.class);
    String invocationId =
        String.format("%s_%s", flinkOptions.getJobName(), UUID.randomUUID().toString());

    FlinkPipelineRunner runner =
        new FlinkPipelineRunner(
            flinkOptions,
            configuration.flinkConfDir,
            detectClassPathResourcesToStage(
                FlinkPipelineRunner.class.getClassLoader(), flinkOptions));
    JobInfo jobInfo =
        JobInfo.create(
            invocationId,
            flinkOptions.getJobName(),
            retrievalToken,
            PipelineOptionsTranslation.toProto(flinkOptions));
    try {
      runner.run(pipeline, jobInfo);
    } catch (Exception e) {
      throw new RuntimeException(String.format("Job %s failed.", invocationId), e);
    }
    LOG.info("Job {} finished successfully.", invocationId);
  }

  private static class FlinkPipelineRunnerConfiguration {
    @Option(
        name = "--flink-conf-dir",
        usage =
            "Directory containing Flink YAML configuration files. "
                + "These properties will be set to all jobs submitted to Flink and take precedence "
                + "over configurations in FLINK_CONF_DIR.")
    private String flinkConfDir = null;

    @Option(
        name = "--base-job-name",
        usage =
            "The job to run. This must correspond to a subdirectory of the jar's BEAM-PIPELINE "
                + "directory. *Only needs to be specified if the jar contains multiple pipelines.*")
    private String baseJobName = null;
  }

  private static FlinkPipelineRunnerConfiguration parseArgs(String[] args) {
    FlinkPipelineRunnerConfiguration configuration = new FlinkPipelineRunnerConfiguration();
    CmdLineParser parser = new CmdLineParser(configuration);
    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      LOG.error("Unable to parse command line arguments.", e);
      parser.printUsage(System.err);
      throw new IllegalArgumentException("Unable to parse command line arguments.", e);
    }
    return configuration;
  }
}
