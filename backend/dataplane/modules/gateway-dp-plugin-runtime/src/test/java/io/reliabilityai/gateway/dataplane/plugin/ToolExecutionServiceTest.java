package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCostSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginInvocationCost;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolDefinition;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolExecutionResult;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolRequest;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolResponse;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginLifecycleService;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRegistryService;
import io.reliabilityai.gateway.dataplane.plugin.application.ToolExecutionService;
import io.reliabilityai.gateway.dataplane.plugin.internal.InProcessSandbox;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tool execution: admission, isolation, budgets, tenancy, cost and concurrency (Doc 28 §15, §REC).
 */
@DisplayName("tool execution")
class ToolExecutionServiceTest {

  private static final long TOOL_COST_MICROS = 250L;

  private final PluginFixture.TestClock clock = new PluginFixture.TestClock();
  private final InProcessSandbox sandbox = new InProcessSandbox(256);
  private final List<PluginInvocationCost> recordedCosts = new CopyOnWriteArrayList<>();
  private final PluginCostSinkPort costs = recordedCosts::add;

  private final PluginRegistryService registry =
      new PluginRegistryService(
          PluginFixture.trustAll(),
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          64);
  private final PluginLifecycleService lifecycle =
      new PluginLifecycleService(
          registry,
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          PluginFixture.TENANT);

  private ToolExecutionService execution(final PluginAuthorizationPort authorization) {
    return new ToolExecutionService(
        registry,
        sandbox,
        authorization,
        PluginAuditSinkPort.NO_OP,
        PluginTelemetryPort.NO_OP,
        costs,
        clock,
        TOOL_COST_MICROS);
  }

  private static PluginManifest echoManifest(final String id, final Duration wallClock) {
    return new PluginManifest(
        PluginId.of(id),
        id,
        new io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion(1, 0, 0),
        "acme",
        "echo plugin",
        io.reliabilityai.gateway.dataplane.plugin.api.PluginType.INTERNAL,
        io.reliabilityai.gateway.dataplane.plugin.api.TrustTier.FIRST_PARTY,
        Set.of(),
        0,
        io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions.none(),
        PluginCapabilities.of(
            PluginCapabilities.TOOL_EXECUTION, PluginCapabilities.READ_TENANT_SCOPE),
        List.of(new ToolDefinition("echo", "echoes", "{}")),
        PluginFixture.budget(wallClock),
        java.util.Map.of(),
        PluginFixture.health(),
        List.of());
  }

  private TestPlugins.FunctionTool bindEcho(final String id) {
    final PluginManifest manifest = echoManifest(id, Duration.ofSeconds(2));
    final TestPlugins.FunctionTool plugin =
        new TestPlugins.FunctionTool(
            manifest, invocation -> ToolResponse.of("echo:" + invocation.request().arguments()));
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of(id));
    return plugin;
  }

  @Test
  @DisplayName("a READY plugin executes its declared tool")
  void readyPluginExecutes() {
    bindEcho("echo-1");
    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("echo-1"),
                ToolRequest.of("echo", "hello"),
                PluginFixture.TENANT,
                new CorrelationId("c-1"));

    assertThat(result).isInstanceOf(ToolExecutionResult.Completed.class);
    assertThat(((ToolExecutionResult.Completed) result).response().output())
        .isEqualTo("echo:hello");
  }

  @Test
  @DisplayName("an unregistered plugin is isolated, never thrown")
  void unregisteredPluginIsIsolated() {
    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("absent"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-2"));

    assertThat(result).isInstanceOf(ToolExecutionResult.Isolated.class);
  }

  @Test
  @DisplayName("a bound but unstarted plugin refuses execution")
  void unstartedPluginRefusesExecution() {
    final PluginManifest manifest = echoManifest("never-started", Duration.ofSeconds(1));
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FunctionTool(manifest, invocation -> ToolResponse.of("x")));

    assertThat(
            execution(PluginAuthorizationPort.PERMIT_ALL)
                .execute(
                    PluginId.of("never-started"),
                    ToolRequest.of("echo", "x"),
                    PluginFixture.TENANT,
                    new CorrelationId("c-3")))
        .isInstanceOf(ToolExecutionResult.Isolated.class);
  }

  @Test
  @DisplayName("a tool the signed manifest never declared is refused")
  void undeclaredToolIsRefused() {
    bindEcho("echo-2");
    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("echo-2"),
                ToolRequest.of("exfiltrate", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-4"));

    // The plugin's code might happily answer to this name; the vetted manifest is what decides.
    assertThat(result).isInstanceOf(ToolExecutionResult.Isolated.class);
    assertThat(((ToolExecutionResult.Isolated) result).error().kind())
        .isEqualTo(PluginFailureKind.PERMISSION);
  }

  @Test
  @DisplayName("governance denies and the invocation never reaches the plugin")
  void governanceDenialBlocksExecution() {
    final TestPlugins.FunctionTool plugin = bindEcho("echo-3");
    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.DENY_ALL)
            .execute(
                PluginId.of("echo-3"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-5"));

    assertThat(((ToolExecutionResult.Isolated) result).error().kind())
        .isEqualTo(PluginFailureKind.PERMISSION);
    assertThat(plugin.invocations.get()).isZero();
  }

  @Test
  @DisplayName("an indeterminate authorization decision denies, deny-by-default")
  void indeterminateAuthorizationDenies() {
    bindEcho("echo-4");
    final ToolExecutionResult result =
        execution((tenant, descriptor) -> PluginAuthorizationPort.Decision.INDETERMINATE)
            .execute(
                PluginId.of("echo-4"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-6"));

    // AD-019: policy that could not be evaluated is a denial, never a pass.
    assertThat(result).isInstanceOf(ToolExecutionResult.Isolated.class);
  }

  @Test
  @DisplayName("authorization is asked per tenant, so one tenant's grant is not another's")
  void authorizationIsPerTenant() {
    bindEcho("echo-5");
    final ToolExecutionService service =
        execution(
            (tenant, descriptor) ->
                PluginFixture.TENANT.equals(tenant)
                    ? PluginAuthorizationPort.Decision.PERMIT
                    : PluginAuthorizationPort.Decision.DENY);

    assertThat(
            service.execute(
                PluginId.of("echo-5"),
                ToolRequest.of("echo", "a"),
                PluginFixture.TENANT,
                new CorrelationId("c-7")))
        .isInstanceOf(ToolExecutionResult.Completed.class);
    assertThat(
            service.execute(
                PluginId.of("echo-5"),
                ToolRequest.of("echo", "a"),
                PluginFixture.OTHER_TENANT,
                new CorrelationId("c-8")))
        .isInstanceOf(ToolExecutionResult.Isolated.class);
  }

  @Test
  @DisplayName("a plugin sees only its own invocation's tenant scope")
  void pluginSeesOnlyItsInvocationTenant() {
    final TestPlugins.FunctionTool plugin = bindEcho("echo-6");
    final ToolExecutionService service = execution(PluginAuthorizationPort.PERMIT_ALL);

    service.execute(
        PluginId.of("echo-6"),
        ToolRequest.of("echo", "a"),
        PluginFixture.TENANT,
        new CorrelationId("c-9"));
    assertThat(plugin.lastContext.get().tenantScope()).isEqualTo(PluginFixture.TENANT);

    service.execute(
        PluginId.of("echo-6"),
        ToolRequest.of("echo", "b"),
        PluginFixture.OTHER_TENANT,
        new CorrelationId("c-10"));
    // Nothing from the first invocation carries into the second: the context is rebuilt per call.
    assertThat(plugin.lastContext.get().tenantScope()).isEqualTo(PluginFixture.OTHER_TENANT);
  }

  @Test
  @DisplayName("a plugin without the tenant-scope capability gets a redacted scope")
  void tenantScopeIsCapabilityGated() {
    final PluginManifest manifest =
        new PluginManifest(
            PluginId.of("blind"),
            "blind",
            new io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion(1, 0, 0),
            "acme",
            "d",
            io.reliabilityai.gateway.dataplane.plugin.api.PluginType.INTERNAL,
            io.reliabilityai.gateway.dataplane.plugin.api.TrustTier.FIRST_PARTY,
            Set.of(),
            0,
            io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions.none(),
            PluginCapabilities.of(PluginCapabilities.TOOL_EXECUTION),
            List.of(new ToolDefinition("echo", "d", "{}")),
            PluginFixture.budget(Duration.ofSeconds(1)),
            java.util.Map.of(),
            PluginFixture.health(),
            List.of());
    final TestPlugins.FunctionTool plugin =
        new TestPlugins.FunctionTool(manifest, invocation -> ToolResponse.of("ok"));
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of("blind"));

    execution(PluginAuthorizationPort.PERMIT_ALL)
        .execute(
            PluginId.of("blind"),
            ToolRequest.of("echo", "x"),
            PluginFixture.TENANT,
            new CorrelationId("c-11"));

    assertThat(plugin.lastContext.get().tenantScope().tenant()).isEqualTo("redacted");
  }

  @Test
  @DisplayName("a plugin that overruns its deadline is isolated as a timeout")
  void deadlineBreachIsolatesAsTimeout() {
    final PluginManifest manifest = echoManifest("slowpoke", Duration.ofMillis(120));
    final TestPlugins.SlowTool plugin = new TestPlugins.SlowTool(manifest, Duration.ofSeconds(30));
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of("slowpoke"));

    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("slowpoke"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-12"));

    assertThat(((ToolExecutionResult.Isolated) result).error().kind())
        .isEqualTo(PluginFailureKind.TIMEOUT);
  }

  @Test
  @DisplayName("cancellation reaches the plugin, not just the caller")
  void cancellationPropagatesToThePlugin() throws Exception {
    final PluginManifest manifest = echoManifest("interruptible", Duration.ofMillis(150));
    final TestPlugins.SlowTool plugin = new TestPlugins.SlowTool(manifest, Duration.ofSeconds(30));
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of("interruptible"));

    execution(PluginAuthorizationPort.PERMIT_ALL)
        .execute(
            PluginId.of("interruptible"),
            ToolRequest.of("echo", "x"),
            PluginFixture.TENANT,
            new CorrelationId("c-13"));

    assertThat(plugin.entered.await(2, TimeUnit.SECONDS)).isTrue();
    // The interrupt is what makes the deadline real: without it the caller is released and the
    // plugin keeps running.
    for (int attempt = 0; attempt < 100 && plugin.interruptions.get() == 0; attempt++) {
      Thread.sleep(10);
    }
    assertThat(plugin.interruptions.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("the caller's remaining time clamps the plugin's budget")
  void callerRemainingTimeClampsTheBudget() {
    final PluginManifest manifest = echoManifest("patient", Duration.ofSeconds(30));
    final TestPlugins.SlowTool plugin = new TestPlugins.SlowTool(manifest, Duration.ofSeconds(30));
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of("patient"));

    final long startedNanos = System.nanoTime();
    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("patient"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-14"),
                Duration.ofMillis(150));
    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

    // Doc 28 REC-3: a plugin must never outlive the request that triggered it.
    assertThat(((ToolExecutionResult.Isolated) result).error().kind())
        .isEqualTo(PluginFailureKind.TIMEOUT);
    assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
  }

  @Test
  @DisplayName("a throwing plugin is classified as a plugin bug")
  void throwingPluginIsAPluginBug() {
    final PluginManifest manifest = echoManifest("thrower", Duration.ofSeconds(1));
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.ThrowingTool(manifest, new IllegalStateException("boom")));
    lifecycle.start(PluginId.of("thrower"));

    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("thrower"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-15"));

    assertThat(((ToolExecutionResult.Isolated) result).error().kind())
        .isEqualTo(PluginFailureKind.PLUGIN_BUG);
  }

  @Test
  @DisplayName("an IO failure classifies as network, not as a plugin bug")
  void ioFailureClassifiesAsNetwork() {
    final PluginManifest manifest = echoManifest("io-failer", Duration.ofSeconds(1));
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.ThrowingTool(manifest, new IOException("connection reset")));
    lifecycle.start(PluginId.of("io-failer"));

    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("io-failer"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-16"));

    assertThat(((ToolExecutionResult.Isolated) result).error().kind())
        .isEqualTo(PluginFailureKind.NETWORK);
  }

  @Test
  @DisplayName("a security exception classifies as a permission violation")
  void securityExceptionIsAViolation() {
    final PluginManifest manifest = echoManifest("trespasser", Duration.ofSeconds(1));
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.ThrowingTool(manifest, new SecurityException("denied")));
    lifecycle.start(PluginId.of("trespasser"));

    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("trespasser"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-17"));

    final ToolExecutionResult.Isolated isolated = (ToolExecutionResult.Isolated) result;
    assertThat(isolated.error().kind()).isEqualTo(PluginFailureKind.PERMISSION);
    assertThat(isolated.error().violation()).isTrue();
  }

  @Test
  @DisplayName("a plugin returning null is isolated rather than reported as an empty success")
  void nullResponseIsIsolated() {
    final PluginManifest manifest = echoManifest("null-returner", Duration.ofSeconds(1));
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FunctionTool(manifest, invocation -> null));
    lifecycle.start(PluginId.of("null-returner"));

    assertThat(
            execution(PluginAuthorizationPort.PERMIT_ALL)
                .execute(
                    PluginId.of("null-returner"),
                    ToolRequest.of("echo", "x"),
                    PluginFixture.TENANT,
                    new CorrelationId("c-18")))
        .isInstanceOf(ToolExecutionResult.Isolated.class);
  }

  @Test
  @DisplayName("an error message never reaches the isolated result")
  void errorMessagesNeverEscape() {
    final PluginManifest manifest = echoManifest("leaky", Duration.ofSeconds(1));
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.ThrowingTool(
            manifest, new IllegalStateException("customer-ssn=123-45-6789")));
    lifecycle.start(PluginId.of("leaky"));

    final ToolExecutionResult result =
        execution(PluginAuthorizationPort.PERMIT_ALL)
            .execute(
                PluginId.of("leaky"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-19"));

    // The message is arbitrary third-party text that could contain anything it was processing.
    assertThat(((ToolExecutionResult.Isolated) result).error().code()).doesNotContain("123-45");
    assertThat(((ToolExecutionResult.Isolated) result).error().code()).isEqualTo("plugin_bug");
  }

  @Test
  @DisplayName("every invocation emits a cost record, including failures")
  void everyInvocationEmitsCost() {
    bindEcho("echo-7");
    final PluginManifest broken = echoManifest("broken-cost", Duration.ofSeconds(1));
    registry.register(
        PluginFixture.snapshot(broken),
        new TestPlugins.ThrowingTool(broken, new IllegalStateException("x")));
    lifecycle.start(PluginId.of("broken-cost"));

    final ToolExecutionService service = execution(PluginAuthorizationPort.PERMIT_ALL);
    recordedCosts.clear();
    service.execute(
        PluginId.of("echo-7"),
        ToolRequest.of("echo", "a"),
        PluginFixture.TENANT,
        new CorrelationId("c-20"));
    service.execute(
        PluginId.of("broken-cost"),
        ToolRequest.of("echo", "a"),
        PluginFixture.TENANT,
        new CorrelationId("c-21"));

    // A plugin that fails every call must not look free.
    assertThat(recordedCosts).hasSize(2);
    assertThat(recordedCosts).allMatch(cost -> cost.providerCostMicros() == 0L);
    assertThat(recordedCosts).allMatch(cost -> cost.toolCostMicros() == TOOL_COST_MICROS);
  }

  @Test
  @DisplayName("a refused invocation still emits a cost record attributed to the tenant")
  void refusedInvocationEmitsCost() {
    bindEcho("echo-8");
    recordedCosts.clear();
    execution(PluginAuthorizationPort.DENY_ALL)
        .execute(
            PluginId.of("echo-8"),
            ToolRequest.of("echo", "x"),
            PluginFixture.TENANT,
            new CorrelationId("c-22"));

    assertThat(recordedCosts).hasSize(1);
    assertThat(recordedCosts.get(0).tenantScope()).isEqualTo(PluginFixture.TENANT);
  }

  @Test
  @DisplayName("the cost record names the plugin and tool it accounts for")
  void costRecordIdentifiesTheWork() {
    bindEcho("echo-9");
    recordedCosts.clear();
    execution(PluginAuthorizationPort.PERMIT_ALL)
        .execute(
            PluginId.of("echo-9"),
            ToolRequest.of("echo", "x"),
            PluginFixture.TENANT,
            new CorrelationId("c-23"));

    assertThat(recordedCosts.get(0).pluginId().value()).isEqualTo("echo-9");
    assertThat(recordedCosts.get(0).toolName()).isEqualTo("echo");
  }

  @Test
  @DisplayName("a failing cost sink cannot fail the invocation it accounts for")
  void costSinkFailureIsSwallowed() {
    bindEcho("echo-10");
    final ToolExecutionService service =
        new ToolExecutionService(
            registry,
            sandbox,
            PluginAuthorizationPort.PERMIT_ALL,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            cost -> {
              throw new IllegalStateException("sink down");
            },
            clock,
            TOOL_COST_MICROS);

    assertThat(
            service.execute(
                PluginId.of("echo-10"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-24")))
        .isInstanceOf(ToolExecutionResult.Completed.class);
  }

  @Test
  @DisplayName("a failing audit sink cannot fail the invocation either")
  void auditSinkFailureIsSwallowed() {
    bindEcho("echo-11");
    final ToolExecutionService service =
        new ToolExecutionService(
            registry,
            sandbox,
            PluginAuthorizationPort.PERMIT_ALL,
            record -> {
              throw new IllegalStateException("audit down");
            },
            PluginTelemetryPort.NO_OP,
            costs,
            clock,
            TOOL_COST_MICROS);

    assertThat(
            service.execute(
                PluginId.of("echo-11"),
                ToolRequest.of("echo", "x"),
                PluginFixture.TENANT,
                new CorrelationId("c-25")))
        .isInstanceOf(ToolExecutionResult.Completed.class);
  }

  @Test
  @DisplayName("a disabled plugin refuses execution immediately")
  void disabledPluginRefusesExecution() {
    bindEcho("echo-12");
    lifecycle.disable(PluginId.of("echo-12"));

    assertThat(
            execution(PluginAuthorizationPort.PERMIT_ALL)
                .execute(
                    PluginId.of("echo-12"),
                    ToolRequest.of("echo", "x"),
                    PluginFixture.TENANT,
                    new CorrelationId("c-26")))
        .isInstanceOf(ToolExecutionResult.Isolated.class);
  }

  @Test
  @DisplayName("100 concurrent executions all complete and leave nothing in flight")
  void hundredConcurrentExecutionsComplete() throws Exception {
    bindEcho("echo-concurrent");
    final ToolExecutionService service = execution(PluginAuthorizationPort.PERMIT_ALL);

    final int callers = 100;
    final CountDownLatch ready = new CountDownLatch(callers);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(callers);
    final AtomicInteger completed = new AtomicInteger();

    for (int index = 0; index < callers; index++) {
      final int caller = index;
      Thread.ofVirtual()
          .start(
              () -> {
                ready.countDown();
                try {
                  go.await();
                  final ToolExecutionResult result =
                      service.execute(
                          PluginId.of("echo-concurrent"),
                          ToolRequest.of("echo", "call-" + caller),
                          PluginFixture.TENANT,
                          new CorrelationId("cc-" + caller));
                  if (result instanceof ToolExecutionResult.Completed done1
                      && done1.response().output().equals("echo:call-" + caller)) {
                    completed.incrementAndGet();
                  }
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }

    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    go.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

    // Every caller got its own answer — no crosstalk between concurrent invocations.
    assertThat(completed.get()).isEqualTo(callers);
    assertThat(sandbox.inFlight()).isZero();
  }

  @Test
  @DisplayName("the substrate refuses work past its concurrency ceiling rather than growing")
  void concurrencyCeilingIsEnforced() throws Exception {
    final InProcessSandbox tiny = new InProcessSandbox(1);
    final PluginRegistryService tinyRegistry =
        new PluginRegistryService(
            PluginFixture.trustAll(),
            tiny,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            clock,
            8);
    final PluginManifest manifest = echoManifest("hog", Duration.ofSeconds(5));
    final TestPlugins.SlowTool plugin = new TestPlugins.SlowTool(manifest, Duration.ofSeconds(2));
    tinyRegistry.register(PluginFixture.snapshot(manifest), plugin);
    new PluginLifecycleService(
            tinyRegistry,
            tiny,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            clock,
            PluginFixture.TENANT)
        .start(PluginId.of("hog"));

    final ToolExecutionService service =
        new ToolExecutionService(
            tinyRegistry,
            tiny,
            PluginAuthorizationPort.PERMIT_ALL,
            PluginAuditSinkPort.NO_OP,
            PluginTelemetryPort.NO_OP,
            costs,
            clock,
            TOOL_COST_MICROS);

    Thread.ofVirtual()
        .start(
            () ->
                service.execute(
                    PluginId.of("hog"),
                    ToolRequest.of("echo", "first"),
                    PluginFixture.TENANT,
                    new CorrelationId("h-1")));
    assertThat(plugin.entered.await(5, TimeUnit.SECONDS)).isTrue();

    // The second caller is refused rather than queued: an unbounded queue is how a slow plugin
    // turns
    // into host memory exhaustion.
    final ToolExecutionResult second =
        service.execute(
            PluginId.of("hog"),
            ToolRequest.of("echo", "second"),
            PluginFixture.TENANT,
            new CorrelationId("h-2"));
    assertThat(((ToolExecutionResult.Isolated) second).error().kind())
        .isEqualTo(PluginFailureKind.RESOURCE_EXCEEDED);
    tiny.shutdown();
  }
}
