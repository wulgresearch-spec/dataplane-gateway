package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.dataplane.plugin.api.IsolationLevel;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxOutcome;
import io.reliabilityai.gateway.dataplane.plugin.internal.InProcessSandbox;
import io.reliabilityai.gateway.dataplane.plugin.internal.ProcessSandbox;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The isolation substrates and what they honestly guarantee (Doc 28 §ISO, §REC). */
@DisplayName("sandbox substrates")
class SandboxTest {

  private static SandboxInvocation invocation(final String id, final Duration wallClock) {
    return new SandboxInvocation(
        PluginId.of(id),
        new CorrelationId("sandbox-" + id),
        PluginFixture.budget(wallClock),
        PluginFixture.EPOCH.plus(wallClock));
  }

  @Test
  @DisplayName(
      "the in-process substrate reports IN_PROCESS and never claims to contain untrusted code")
  void inProcessSubstrateIsHonest() {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    assertThat(sandbox.isolation()).isEqualTo(IsolationLevel.IN_PROCESS);
    // Doc 28 ISO-8/ISO-10: no overclaim. The registry reads this to refuse third-party code.
    assertThat(sandbox.isolation().containsUntrustedCode()).isFalse();
    sandbox.shutdown();
  }

  @Test
  @DisplayName("the process substrate reports PROCESS and does contain untrusted code")
  void processSubstrateContainsUntrustedCode() {
    final ProcessSandbox sandbox = new ProcessSandbox(4);
    assertThat(sandbox.isolation()).isEqualTo(IsolationLevel.PROCESS);
    assertThat(sandbox.isolation().containsUntrustedCode()).isTrue();
    sandbox.shutdown();
  }

  @Test
  @DisplayName("the in-process substrate supports only INTERNAL")
  void inProcessSupportsOnlyInternal() {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    assertThat(sandbox.supports(PluginType.INTERNAL)).isTrue();
    for (final PluginType type : PluginType.values()) {
      if (type != PluginType.INTERNAL) {
        assertThat(sandbox.supports(type)).as("type %s", type).isFalse();
      }
    }
    sandbox.shutdown();
  }

  @Test
  @DisplayName("the process substrate supports the boundary-needing types but not reserved ones")
  void processSupportsBoundaryTypes() {
    final ProcessSandbox sandbox = new ProcessSandbox(4);
    assertThat(sandbox.supports(PluginType.PROCESS)).isTrue();
    assertThat(sandbox.supports(PluginType.REMOTE)).isTrue();
    assertThat(sandbox.supports(PluginType.MCP)).isTrue();
    assertThat(sandbox.supports(PluginType.WASM)).isFalse();
    assertThat(sandbox.supports(PluginType.PYTHON)).isFalse();
    // INTERNAL is excluded for cost, not safety: a process to run first-party in-JVM code is waste.
    assertThat(sandbox.supports(PluginType.INTERNAL)).isFalse();
    sandbox.shutdown();
  }

  @Test
  @DisplayName("work finishing within budget completes and reports its usage")
  void completedWorkReportsUsage() {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    final SandboxOutcome<String> outcome =
        sandbox.run(invocation("quick", Duration.ofSeconds(5)), () -> "done");

    assertThat(outcome).isInstanceOf(SandboxOutcome.Completed.class);
    assertThat(((SandboxOutcome.Completed<String>) outcome).value()).isEqualTo("done");
    assertThat(outcome.usage().wallClock()).isGreaterThanOrEqualTo(Duration.ZERO);
    sandbox.shutdown();
  }

  @Test
  @DisplayName("work exceeding its deadline is breached and the worker is interrupted")
  void deadlineBreachInterruptsTheWorker() throws Exception {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    final CountDownLatch entered = new CountDownLatch(1);
    final AtomicBoolean interrupted = new AtomicBoolean();

    final SandboxOutcome<String> outcome =
        sandbox.run(
            invocation("slow", Duration.ofMillis(120)),
            () -> {
              entered.countDown();
              try {
                Thread.sleep(30_000L);
              } catch (final InterruptedException expected) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw expected;
              }
              return "never";
            });

    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(outcome).isInstanceOf(SandboxOutcome.Breached.class);
    assertThat(((SandboxOutcome.Breached<String>) outcome).kind())
        .isEqualTo(PluginFailureKind.TIMEOUT);

    for (int attempt = 0; attempt < 200 && !interrupted.get(); attempt++) {
      Thread.sleep(5);
    }
    assertThat(interrupted.get()).isTrue();
    sandbox.shutdown();
  }

  @Test
  @DisplayName("a throwing task is classified, and the throwable never crosses the boundary")
  void throwingTaskIsClassified() {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    final SandboxOutcome<String> outcome =
        sandbox.run(
            invocation("thrower", Duration.ofSeconds(5)),
            () -> {
              throw new IOException("socket died");
            });

    assertThat(outcome).isInstanceOf(SandboxOutcome.Threw.class);
    assertThat(((SandboxOutcome.Threw<String>) outcome).kind())
        .isEqualTo(PluginFailureKind.NETWORK);
    sandbox.shutdown();
  }

  @Test
  @DisplayName("an Error is contained and classified as a panic rather than escaping")
  void errorsAreContained() {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    final SandboxOutcome<String> outcome =
        sandbox.run(
            invocation("panicker", Duration.ofSeconds(5)),
            () -> {
              throw new AssertionError("plugin assertion");
            });

    // Letting an Error escape a virtual thread kills it silently and leaves the caller waiting out
    // the deadline for a result that is never coming.
    assertThat(((SandboxOutcome.Threw<String>) outcome).kind()).isEqualTo(PluginFailureKind.PANIC);
    sandbox.shutdown();
  }

  @Test
  @DisplayName("memory exhaustion classifies as a resource breach, not a panic")
  void memoryExhaustionIsAResourceBreach() {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    final SandboxOutcome<String> outcome =
        sandbox.run(
            invocation("hungry", Duration.ofSeconds(5)),
            () -> {
              throw new OutOfMemoryError("synthetic");
            });

    assertThat(((SandboxOutcome.Threw<String>) outcome).kind())
        .isEqualTo(PluginFailureKind.RESOURCE_EXCEEDED);
    sandbox.shutdown();
  }

  @Test
  @DisplayName("the concurrency ceiling refuses rather than queues")
  void concurrencyCeilingRefuses() throws Exception {
    final InProcessSandbox sandbox = new InProcessSandbox(1);
    final CountDownLatch occupied = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    Thread.ofVirtual()
        .start(
            () ->
                sandbox.run(
                    invocation("occupant", Duration.ofSeconds(10)),
                    () -> {
                      occupied.countDown();
                      release.await();
                      return "held";
                    }));
    assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

    final SandboxOutcome<String> refused =
        sandbox.run(invocation("latecomer", Duration.ofSeconds(5)), () -> "should not run");
    assertThat(((SandboxOutcome.Breached<String>) refused).kind())
        .isEqualTo(PluginFailureKind.RESOURCE_EXCEEDED);

    release.countDown();
    sandbox.shutdown();
  }

  @Test
  @DisplayName("in-flight returns to zero once work settles")
  void inFlightSettlesToZero() {
    final InProcessSandbox sandbox = new InProcessSandbox(8);
    for (int index = 0; index < 20; index++) {
      sandbox.run(invocation("burst-" + index, Duration.ofSeconds(5)), () -> "ok");
    }
    assertThat(sandbox.inFlight()).isZero();
    sandbox.shutdown();
  }

  @Test
  @DisplayName("a shut-down substrate admits no further work")
  void shutdownSubstrateAdmitsNothing() {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    sandbox.shutdown();

    assertThat(sandbox.run(invocation("after", Duration.ofSeconds(5)), () -> "nope"))
        .isInstanceOf(SandboxOutcome.Cancelled.class);
  }

  @Test
  @DisplayName("shutdown is idempotent")
  void shutdownIsIdempotent() {
    final InProcessSandbox sandbox = new InProcessSandbox(4);
    sandbox.shutdown();
    sandbox.shutdown();
    assertThat(sandbox.inFlight()).isZero();
  }

  @Test
  @DisplayName("a substrate refuses to accept a non-positive concurrency ceiling")
  void concurrencyCeilingMustBePositive() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new InProcessSandbox(0))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProcessSandbox(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("the process substrate destroys a bound child on a deadline breach")
  void processSubstrateKillsOnDeadlineBreach() throws Exception {
    final ProcessSandbox sandbox = new ProcessSandbox(4);
    final CorrelationId correlationId = new CorrelationId("kill-me");
    final Process child =
        new ProcessBuilder(javaCommand("sleep")).redirectErrorStream(true).start();
    sandbox.bind(correlationId, child);

    final SandboxOutcome<String> outcome =
        sandbox.run(
            new SandboxInvocation(
                PluginId.of("killer"),
                correlationId,
                PluginFixture.budget(Duration.ofMillis(300)),
                PluginFixture.EPOCH.plusMillis(300)),
            () -> {
              // Blocks on the child's output. Only destroying the process releases this — an
              // interrupt alone would leave the caller free and the child running.
              child.getInputStream().read();
              return "read-something";
            });

    assertThat(outcome).isInstanceOf(SandboxOutcome.Breached.class);
    assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(child.isAlive()).isFalse();
    sandbox.shutdown();
  }

  @Test
  @DisplayName("unbinding stops a later breach from killing an unrelated process")
  void unbindPreventsStaleKills() throws Exception {
    final ProcessSandbox sandbox = new ProcessSandbox(4);
    final CorrelationId correlationId = new CorrelationId("unbind-me");
    final Process child = new ProcessBuilder(javaCommand("sleep")).start();
    sandbox.bind(correlationId, child);
    sandbox.unbind(correlationId);

    sandbox.terminate(correlationId);
    assertThat(child.isAlive()).isTrue();

    child.destroyForcibly();
    sandbox.shutdown();
  }

  @Test
  @DisplayName("shutdown destroys every child, so none outlives the runtime")
  void shutdownDestroysEveryChild() throws Exception {
    final ProcessSandbox sandbox = new ProcessSandbox(4);
    final Process first = new ProcessBuilder(javaCommand("sleep")).start();
    final Process second = new ProcessBuilder(javaCommand("sleep")).start();
    sandbox.bind(new CorrelationId("child-1"), first);
    sandbox.bind(new CorrelationId("child-2"), second);

    sandbox.shutdown();

    assertThat(first.waitFor(10, TimeUnit.SECONDS)).isTrue();
    assertThat(second.waitFor(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName("terminating an unbound invocation is a safe no-op")
  void terminateOfUnknownInvocationIsSafe() {
    final ProcessSandbox sandbox = new ProcessSandbox(4);
    sandbox.terminate(new CorrelationId("never-bound"));
    sandbox.shutdown();
  }

  /** The command line that runs {@link EchoPluginProcess} in a fresh JVM. */
  static java.util.List<String> javaCommand(final String mode) {
    return java.util.List.of(
        System.getProperty("java.home")
            + java.io.File.separator
            + "bin"
            + java.io.File.separator
            + "java",
        "-cp",
        System.getProperty("java.class.path"),
        EchoPluginProcess.class.getName(),
        mode);
  }
}
