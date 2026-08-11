package io.reliabilityai.gateway.dataplane.app.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.StartupValidationException;
import io.reliabilityai.gateway.dataplane.app.runtime.ExternalAdapters;
import io.reliabilityai.gateway.dataplane.app.runtime.GatewayRuntime;
import io.reliabilityai.gateway.dataplane.app.runtime.RuntimeFixture;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The AD-018 non-bypass check (Doc 38 §IR-2 required build gate).
 *
 * <p>Two independent guarantees are proven here. First, that the assembled chain consults
 * <b>every</b> mandatory stage, in the frozen order, with no stage skipped and none entered twice —
 * this is the property that stops a refactor from quietly dropping governance or metering out of
 * the hot path. Second, that the pipeline is unreachable unless the runtime is {@code READY}, so a
 * request can never enter a node whose WAL is unreplayed or whose broker has been drained.
 */
class NonBypassIntegrationTest {

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  @Test
  void assembledChainCoversEveryMandatoryStageInFrozenOrder() {
    final RequestPipelineTest.Harness harness = new RequestPipelineTest.Harness();

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    // The frozen pipeline order, verbatim from the enum — the assembly may not reorder it.
    assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());
    assertThat(outcome.trace().stages()).hasSize(MandatoryStage.values().length);
  }

  @Test
  void noMandatoryStageIsSkipped() {
    final RequestPipelineTest.Harness harness = new RequestPipelineTest.Harness();

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    final Set<MandatoryStage> consulted = EnumSet.copyOf(outcome.trace().stages());
    assertThat(consulted).containsExactlyInAnyOrder(MandatoryStage.values());
  }

  @Test
  void everyStageIsEnteredExactlyOnce() {
    final RequestPipelineTest.Harness harness = new RequestPipelineTest.Harness();

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    final List<MandatoryStage> stages = outcome.trace().stages();
    assertThat(stages).doesNotHaveDuplicates();
  }

  @Test
  void refusalTruncatesTheChainAndLeavesDownstreamStagesUnconsulted() {
    final RequestPipelineTest.Harness harness = new RequestPipelineTest.Harness();
    harness.permitted = false;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    final Set<MandatoryStage> consulted = EnumSet.copyOf(outcome.trace().stages());
    // Everything at or below the router must be untouched — a denied request leaves no footprint.
    assertThat(consulted)
        .doesNotContain(
            MandatoryStage.ROUTER,
            MandatoryStage.RELIABILITY,
            MandatoryStage.SECRETS,
            MandatoryStage.ADAPTER,
            MandatoryStage.STREAM_GUARD,
            MandatoryStage.SCHEMA_LOCK,
            MandatoryStage.METERING,
            MandatoryStage.COST,
            MandatoryStage.EMITTER);
  }

  private GatewayRuntime runtime(final ExternalAdapters adapters) {
    return new GatewayRuntime(
        RuntimeFixture.config(
            RuntimeFixture.masterKey(),
            walDir,
            dlqDir.resolve("dlq.log"),
            record -> {},
            new RuntimeFixture.TestClock(),
            adapters));
  }

  @Test
  void pipelineIsUnavailableBeforeStart() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.allExternalAdapters());

    assertThatThrownBy(gateway::pipeline)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not ready");
  }

  @Test
  void pipelineIsAvailableOnlyWhileReadyAndRefusedAfterStop() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.allExternalAdapters());

    gateway.start();
    assertThat(gateway.pipeline()).isNotNull();

    gateway.stop();
    assertThatThrownBy(gateway::pipeline)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not ready");
  }

  @Test
  void aNodeMissingAMandatoryStageNeverAssemblesAPipeline() {
    final GatewayRuntime gateway = runtime(ExternalAdapters.none());

    assertThatThrownBy(gateway::start).isInstanceOf(StartupValidationException.class);
    // Fail-closed: no pipeline is reachable on a node that refused activation.
    assertThatThrownBy(gateway::pipeline).isInstanceOf(IllegalStateException.class);
  }
}
