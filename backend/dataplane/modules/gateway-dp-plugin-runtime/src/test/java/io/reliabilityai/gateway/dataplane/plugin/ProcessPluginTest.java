package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCostSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolDefinition;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolExecutionResult;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolRequest;
import io.reliabilityai.gateway.dataplane.plugin.api.TrustTier;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginLifecycleService;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRegistryService;
import io.reliabilityai.gateway.dataplane.plugin.application.ToolExecutionService;
import io.reliabilityai.gateway.dataplane.plugin.internal.ProcessPluginAdapter;
import io.reliabilityai.gateway.dataplane.plugin.internal.ProcessSandbox;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end execution of a genuinely out-of-process plugin (Doc 28 ISO-1, ISO-2, §36.1).
 *
 * <p>These tests spawn real JVMs. That is the point: the process boundary is the isolation
 * guarantee Doc 28 rests on, and a mocked boundary would prove nothing about it.
 */
@DisplayName("process plugin")
class ProcessPluginTest {

  private final PluginFixture.TestClock clock = new PluginFixture.TestClock();
  private final ProcessSandbox sandbox = new ProcessSandbox(8);
  private final PluginRegistryService registry =
      new PluginRegistryService(
          PluginFixture.trustAll(),
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          16);
  private final PluginLifecycleService lifecycle =
      new PluginLifecycleService(
          registry,
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          PluginFixture.TENANT);
  private final ToolExecutionService execution =
      new ToolExecutionService(
          registry,
          sandbox,
          PluginAuthorizationPort.PERMIT_ALL,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          PluginCostSinkPort.NO_OP,
          clock,
          10L);

  @AfterEach
  void tearDown() {
    sandbox.shutdown();
  }

  private static PluginManifest processManifest(final String id, final Duration wallClock) {
    return new PluginManifest(
        PluginId.of(id),
        id,
        new PluginVersion(1, 0, 0),
        "third-party-vendor",
        "an out-of-process plugin",
        PluginType.PROCESS,
        // Third-party: the tier that Doc 28 ISO-1 forbids in the host JVM.
        TrustTier.THIRD_PARTY,
        Set.of(),
        0,
        PluginPermissions.none(),
        PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION),
        List.of(new ToolDefinition("echo", "echoes", "{}")),
        PluginFixture.budget(wallClock),
        Map.of(),
        PluginFixture.health(),
        List.of());
  }

  private void bind(final String id, final String mode, final Duration wallClock) {
    final PluginManifest manifest = processManifest(id, wallClock);
    registry.register(
        PluginFixture.snapshot(manifest),
        new ProcessPluginAdapter(manifest, SandboxTest.javaCommand(mode), sandbox));
    lifecycle.start(PluginId.of(id));
  }

  @Test
  @DisplayName("a third-party plugin runs out of process and returns its answer")
  void thirdPartyPluginRunsOutOfProcess() {
    bind("out-of-proc", "echo", Duration.ofSeconds(60));

    final ToolExecutionResult result =
        execution.execute(
            PluginId.of("out-of-proc"),
            ToolRequest.of("echo", "hello-from-host"),
            PluginFixture.TENANT,
            new CorrelationId("p-1"));

    assertThat(result).isInstanceOf(ToolExecutionResult.Completed.class);
    assertThat(((ToolExecutionResult.Completed) result).response().output())
        .isEqualTo("child-echo:echo:hello-from-host");
  }

  @Test
  @DisplayName("a child that hangs past the deadline is killed, not merely abandoned")
  void hangingChildIsKilled() {
    bind("hanging", "sleep", Duration.ofMillis(700));

    final long startedNanos = System.nanoTime();
    final ToolExecutionResult result =
        execution.execute(
            PluginId.of("hanging"),
            ToolRequest.of("echo", "x"),
            PluginFixture.TENANT,
            new CorrelationId("p-2"));
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

    assertThat(((ToolExecutionResult.Isolated) result).error().kind())
        .isEqualTo(PluginFailureKind.TIMEOUT);
    // A pipe read is not interruptible; only destroying the child releases the host thread. If this
    // took anywhere near the child's ten-minute sleep, cancellation was not real.
    assertThat(elapsed).isLessThan(Duration.ofSeconds(20));
  }

  @Test
  @DisplayName("a child that dies without replying is isolated, and the host survives")
  void crashingChildIsIsolated() {
    bind("crasher", "crash", Duration.ofSeconds(30));

    final ToolExecutionResult result =
        execution.execute(
            PluginId.of("crasher"),
            ToolRequest.of("echo", "x"),
            PluginFixture.TENANT,
            new CorrelationId("p-3"));

    // Doc 28 ISO-2: the crash is contained to the child. The host records it and carries on.
    assertThat(result).isInstanceOf(ToolExecutionResult.Isolated.class);
  }

  @Test
  @DisplayName("a child reporting its own failure is isolated without forwarding its message")
  void childErrorCodeIsNotForwarded() {
    bind("failer", "fail", Duration.ofSeconds(30));

    final ToolExecutionResult result =
        execution.execute(
            PluginId.of("failer"),
            ToolRequest.of("echo", "x"),
            PluginFixture.TENANT,
            new CorrelationId("p-4"));

    final ToolExecutionResult.Isolated isolated = (ToolExecutionResult.Isolated) result;
    // The child's own text is untrusted and never reaches an audit record or a metric label.
    assertThat(isolated.error().code()).doesNotContain("plugin-said-no");
    assertThat(isolated.error().kind()).isEqualTo(PluginFailureKind.PLUGIN_BUG);
  }

  @Test
  @DisplayName("a process plugin survives repeated invocations with no shared state between them")
  void invocationsShareNoState() {
    bind("stateless", "echo", Duration.ofSeconds(60));

    for (int index = 0; index < 3; index++) {
      final ToolExecutionResult result =
          execution.execute(
              PluginId.of("stateless"),
              ToolRequest.of("echo", "call-" + index),
              PluginFixture.TENANT,
              new CorrelationId("p-loop-" + index));
      // A fresh process per invocation is what makes Doc 28 §36.1's "no cross-request state" true
      // by
      // construction rather than by discipline.
      assertThat(((ToolExecutionResult.Completed) result).response().output())
          .isEqualTo("child-echo:echo:call-" + index);
    }
  }

  @Test
  @DisplayName("a stopped process plugin refuses to spawn anything")
  void stoppedProcessPluginRefuses() {
    bind("stoppable-proc", "echo", Duration.ofSeconds(30));
    lifecycle.stop(PluginId.of("stoppable-proc"));

    assertThat(
            execution.execute(
                PluginId.of("stoppable-proc"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("p-5")))
        .isInstanceOf(ToolExecutionResult.Isolated.class);
  }

  @Test
  @DisplayName("third-party code is accepted on the process substrate that the in-JVM one refused")
  void thirdPartyIsAcceptedOnlyBehindTheBoundary() {
    final PluginManifest manifest = processManifest("boundary-check", Duration.ofSeconds(30));
    assertThat(
            registry
                .register(
                    PluginFixture.snapshot(manifest),
                    new ProcessPluginAdapter(manifest, SandboxTest.javaCommand("echo"), sandbox))
                .bound())
        .isTrue();
  }
}
