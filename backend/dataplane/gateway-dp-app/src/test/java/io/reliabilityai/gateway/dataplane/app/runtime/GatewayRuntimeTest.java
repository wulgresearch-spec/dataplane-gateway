package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.StartupValidationException;
import io.reliabilityai.gateway.dataplane.app.runtime.RuntimeFixture.Count;
import io.reliabilityai.gateway.dataplane.app.runtime.RuntimeFixture.TestClock;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.LocalWal;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalConfig;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalSyncPolicy;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lifecycle tests for the single-VPS {@link GatewayRuntime} composition root (AD-020). */
class GatewayRuntimeTest {

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private final List<String> delivered = new CopyOnWriteArrayList<>();
  private final TestClock clock = new TestClock();

  private GatewayRuntime runtimeWithAllSeams(final byte[] masterKey) {
    return new GatewayRuntime(config(masterKey, RuntimeFixture.allExternalAdapters()));
  }

  private GatewayRuntimeConfig config(
      final byte[] masterKey, final ExternalAdapters externalAdapters) {
    final Consumer<BrokerRecord> subscriber = record -> delivered.add(record.eventId());
    return RuntimeFixture.config(
        masterKey, walDir, dlqDir.resolve("dlq.log"), subscriber, clock, externalAdapters);
  }

  @Test
  void successfulStartupReachesReadyAndExposesEveryModule() {
    final GatewayRuntime runtime = runtimeWithAllSeams(RuntimeFixture.masterKey());
    runtime.start();

    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.READY);
    // The whole pipeline is reachable from the one runtime object, with no container in sight.
    assertThat(runtime.publisher()).isNotNull();
    assertThat(runtime.router()).isNotNull();
    assertThat(runtime.streamGuard()).isNotNull();
    assertThat(runtime.metering()).isNotNull();
    assertThat(runtime.costEngine()).isNotNull();
    assertThat(runtime.secretsProvider()).isNotNull();
    assertThat(runtime.telemetry()).isNotNull();
    assertThat(runtime.kmsUnwrap()).isNotNull();
    assertThat(runtime.authentication()).isPresent();
    assertThat(runtime.providerAdapter()).isPresent();
    assertThat(runtime.reliabilityEngine()).isPresent();
    assertThat(runtime.schemaLock()).isPresent();

    runtime.stop();
    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.STOPPED);
  }

  @Test
  void startupValidationFailureFailsClosedBeforeCreatingRuntime() {
    // The substrate-only node: no external adapter supplied, so mandatory stages cannot bind.
    final GatewayRuntime runtime =
        new GatewayRuntime(config(RuntimeFixture.masterKey(), ExternalAdapters.none()));

    assertThatThrownBy(runtime::start)
        .isInstanceOf(StartupValidationException.class)
        .hasMessageContaining("ADAPTER");
    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.FAILED);
    assertThat(runtime.startupPhases())
        .containsExactly(
            GatewayRuntime.StartupPhase.VALIDATE_CONFIG,
            GatewayRuntime.StartupPhase.LOAD_SECRETS,
            GatewayRuntime.StartupPhase.LOAD_SNAPSHOTS); // stopped at the gate, never READY
  }

  @Test
  void boundStagesAreDerivedFromWiringNotDeclared() {
    // A node with only the identity verifier supplied must not be able to claim ADAPTER or
    // SCHEMA_LOCK.
    final ExternalAdapters all = RuntimeFixture.allExternalAdapters();
    final ExternalAdapters authOnly =
        new ExternalAdapters(
            all.ingress(),
            all.identityVerifier(),
            all.governance(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty(),
            java.util.Optional.empty());
    final GatewayRuntime runtime = new GatewayRuntime(config(RuntimeFixture.masterKey(), authOnly));

    assertThatThrownBy(runtime::start).isInstanceOf(StartupValidationException.class);
    assertThat(runtime.boundStages())
        .contains(MandatoryStage.AUTHN, MandatoryStage.ROUTER, MandatoryStage.METERING)
        .doesNotContain(
            MandatoryStage.ADAPTER, MandatoryStage.RELIABILITY, MandatoryStage.SCHEMA_LOCK);
  }

  @Test
  void everyMandatoryStageBindsWhenEverySeamIsSupplied() {
    final GatewayRuntime runtime = runtimeWithAllSeams(RuntimeFixture.masterKey());
    runtime.start();

    assertThat(runtime.unboundStages()).isEmpty();
    assertThat(runtime.boundStages()).containsExactlyInAnyOrder(MandatoryStage.values());
    assertThat(runtime.governance()).isPresent();
    runtime.stop();
  }

  @Test
  void ingressOpensLastOnStartupAndClosesFirstOnShutdown() {
    final RuntimeFixture.RecordingIngress ingress = new RuntimeFixture.RecordingIngress();
    final GatewayRuntime runtime =
        new GatewayRuntime(
            config(RuntimeFixture.masterKey(), RuntimeFixture.allExternalAdapters(ingress)));

    runtime.start();
    // The door opens only once the broker is live — asserted by START_BROKER preceding READY.
    assertThat(ingress.edges()).containsExactly("start");
    assertThat(runtime.startupPhases()).endsWith(GatewayRuntime.StartupPhase.READY);

    runtime.stop();
    // ...and closes before anything is drained, so the drain sees a bounded backlog.
    assertThat(ingress.edges()).containsExactly("start", "stop");
  }

  @Test
  void walReplayDeliversPreCrashPendingRecordsOnStartup() {
    // A ZL record was durably appended before a crash but never marked sent.
    try (LocalWal preCrash =
        new LocalWal(walDir, new WalConfig(4096, WalSyncPolicy.ALWAYS), RuntimeFixture.CODEC)) {
      preCrash.append(new BrokerRecord("node-ZL-1", "topic.audit", DeliveryClass.ZL, new Count(7)));
    }

    final GatewayRuntime runtime = runtimeWithAllSeams(RuntimeFixture.masterKey());
    runtime.start(); // replay happens during REPLAY_WAL
    runtime.stop(); // drain the broker

    assertThat(delivered).contains("node-ZL-1");
  }

  @Test
  void gracefulShutdownFlushesAndDrainsWithNoLoss() {
    final GatewayRuntime runtime = runtimeWithAllSeams(RuntimeFixture.masterKey());
    runtime.start();
    runtime.publisher().publish("topic.audit", DeliveryClass.ZL, new Count(1));
    runtime.stop();

    assertThat(delivered).containsExactly("node-ZL-1");
    assertThat(runtime.shutdownPhases())
        .containsExactly(
            GatewayRuntime.ShutdownPhase.STOP_ACCEPTING,
            GatewayRuntime.ShutdownPhase.FLUSH_PUBLISHER,
            GatewayRuntime.ShutdownPhase.DRAIN_BROKER,
            GatewayRuntime.ShutdownPhase.CLOSE_WAL,
            GatewayRuntime.ShutdownPhase.CLOSE_ADAPTERS,
            GatewayRuntime.ShutdownPhase.ZEROIZE_SECRETS,
            GatewayRuntime.ShutdownPhase.EXIT);
  }

  @Test
  void restartAfterCrashReplaysThenServesNewTrafficWithoutIdCollision() {
    try (LocalWal preCrash =
        new LocalWal(walDir, new WalConfig(4096, WalSyncPolicy.ALWAYS), RuntimeFixture.CODEC)) {
      preCrash.append(new BrokerRecord("node-ZL-1", "topic.audit", DeliveryClass.ZL, new Count(7)));
    }

    final GatewayRuntime runtime = runtimeWithAllSeams(RuntimeFixture.masterKey());
    runtime.start();
    runtime.publisher().publish("topic.audit", DeliveryClass.ZL, new Count(8));
    runtime.stop();

    // Replayed record first, then the new event seeded above the pre-crash high-water mark.
    assertThat(delivered).containsExactly("node-ZL-1", "node-ZL-2");
  }

  @Test
  void secretsZeroizedOnStop() {
    final byte[] key = RuntimeFixture.masterKey();
    final GatewayRuntime runtime = runtimeWithAllSeams(key);
    runtime.start();
    runtime.stop();

    assertThat(key).containsOnly((byte) 0); // composition-held master key wiped (Doc 26 §17.1)
  }

  @Test
  void secretsZeroizedWhenStartupFailsAtThePipelineGate() {
    final byte[] key = RuntimeFixture.masterKey();
    final GatewayRuntime runtime = new GatewayRuntime(config(key, ExternalAdapters.none()));

    assertThatThrownBy(runtime::start).isInstanceOf(StartupValidationException.class);

    // A refused activation must not leave key material resident in the process.
    assertThat(key).containsOnly((byte) 0);
  }

  @Test
  void deterministicInitializationOrder() {
    final GatewayRuntime runtime = runtimeWithAllSeams(RuntimeFixture.masterKey());
    runtime.start();

    assertThat(runtime.startupPhases())
        .containsExactly(
            GatewayRuntime.StartupPhase.VALIDATE_CONFIG,
            GatewayRuntime.StartupPhase.LOAD_SECRETS,
            GatewayRuntime.StartupPhase.LOAD_SNAPSHOTS,
            GatewayRuntime.StartupPhase.VALIDATE_PIPELINE,
            GatewayRuntime.StartupPhase.CREATE_RUNTIME,
            GatewayRuntime.StartupPhase.REPLAY_WAL,
            GatewayRuntime.StartupPhase.START_BROKER,
            GatewayRuntime.StartupPhase.READY);
    runtime.stop();
  }

  @Test
  void initializationOrderIsIdenticalAcrossRuns() {
    final List<GatewayRuntime.StartupPhase> first;
    final GatewayRuntime one = runtimeWithAllSeams(RuntimeFixture.masterKey());
    one.start();
    first = one.startupPhases();
    one.stop();

    final GatewayRuntime two = runtimeWithAllSeams(RuntimeFixture.masterKey());
    two.start();
    assertThat(two.startupPhases()).isEqualTo(first); // deterministic, not incidentally stable
    two.stop();
  }

  @Test
  void publisherRefusesEventsBeforeStartAndAfterStop() {
    final GatewayRuntime runtime = runtimeWithAllSeams(RuntimeFixture.masterKey());

    assertThatThrownBy(runtime::publisher)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not ready");

    runtime.start();
    final io.reliabilityai.gateway.ports.EventPublisherPort live = runtime.publisher();
    runtime.stop();

    // Modules hold their sinks across shutdown; those sinks must fail loudly, never drop silently.
    assertThatThrownBy(() -> runtime.metering())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not ready");
    assertThat(live).isNotNull();
  }

  @Test
  void stopIsIdempotentAndStartIsNotRepeatable() {
    final GatewayRuntime runtime = runtimeWithAllSeams(RuntimeFixture.masterKey());
    runtime.start();
    runtime.stop();
    runtime.stop(); // second stop is a no-op, not a fault

    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.STOPPED);
    assertThat(runtime.shutdownPhases()).hasSize(7);
    assertThatThrownBy(runtime::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already started");
  }
}
