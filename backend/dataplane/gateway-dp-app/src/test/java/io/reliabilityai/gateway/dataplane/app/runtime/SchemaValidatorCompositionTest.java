package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runtime must construct the schema validator and use it for the SCHEMA_LOCK stage. */
class SchemaValidatorCompositionTest {

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private GatewayRuntime runtime;

  @AfterEach
  void tearDown() {
    if (runtime != null && runtime.state() == GatewayRuntime.State.READY) {
      runtime.stop();
    }
  }

  private GatewayRuntime start(final ExternalAdapters adapters) {
    runtime =
        new GatewayRuntime(
            RuntimeFixture.config(
                RuntimeFixture.masterKey(),
                walDir,
                dlqDir.resolve("dlq.log"),
                record -> {},
                new RuntimeFixture.TestClock(),
                adapters,
                Optional.of(
                    RuntimeFixture.governanceConfig(
                        RuntimeFixture.permittingPolicy(), AuditSinkPort.NO_OP)),
                Optional.of(RuntimeFixture.providerConfig(URI.create("http://127.0.0.1:1"))),
                Optional.empty()));
    runtime.start();
    return runtime;
  }

  @Test
  void schemaLockBindsFromTheConstructedValidator() {
    start(RuntimeFixture.allExternalAdapters());

    assertThat(runtime.schemaLock()).isPresent();
    assertThat(runtime.boundStages()).contains(MandatoryStage.SCHEMA_LOCK);
  }

  @Test
  void schemaLockStaysUnboundWithoutAGenerationDriver() {
    // The validator is the node's own, but re-generation still needs an external driver: the frozen
    // GenerationCommand carries no request or route to re-invoke the provider with.
    final ExternalAdapters all = RuntimeFixture.allExternalAdapters();
    final ExternalAdapters noGeneration =
        new ExternalAdapters(
            all.ingress(),
            all.identityVerifier(),
            all.governance(),
            all.providerTransport(),
            all.providerTranslator(),
            all.credentialPort(),
            all.capabilitySnapshot(),
            all.schemaValidator(),
            Optional.empty());

    runtime =
        new GatewayRuntime(
            RuntimeFixture.config(
                RuntimeFixture.masterKey(),
                walDir,
                dlqDir.resolve("dlq.log"),
                record -> {},
                new RuntimeFixture.TestClock(),
                noGeneration,
                Optional.of(
                    RuntimeFixture.governanceConfig(
                        RuntimeFixture.permittingPolicy(), AuditSinkPort.NO_OP)),
                Optional.of(RuntimeFixture.providerConfig(URI.create("http://127.0.0.1:1"))),
                Optional.empty()));

    org.assertj.core.api.Assertions.assertThatThrownBy(runtime::start)
        .isInstanceOf(io.reliabilityai.gateway.dataplane.app.StartupValidationException.class)
        .hasMessageContaining("SCHEMA_LOCK");
  }

  @Test
  void theExternalValidatorSeamIsNoLongerConsulted() {
    // RuntimeFixture's stub validator returns non-conformant for everything. If the runtime were
    // still
    // using it rather than the constructed engine, SCHEMA_LOCK would behave quite differently.
    start(RuntimeFixture.allExternalAdapters());

    assertThat(runtime.schemaLock()).isPresent();
    assertThat(runtime.unboundStages()).isEmpty();
  }
}
