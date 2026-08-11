package io.reliabilityai.gateway.dataplane.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.ProviderMeta;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Data-driven dispatch tests for the adapter registry (Doc 25 §7.1, no provider-name branching).
 */
class AdapterRegistryTest {

  private static final CanonicalModelId MODEL = new CanonicalModelId("m");
  private static final AttemptBudget BUDGET = new AttemptBudget(Duration.ofSeconds(1));
  private static final CanonicalRequest REQUEST =
      new CanonicalRequest(MODEL, List.of(), List.of(), Map.of());

  private static ProviderAdapterPort constant(
      final ProviderAdapterPort.ProviderInvocationResult r) {
    return (request, route, budget) -> r;
  }

  private static ProviderAdapterPort.ProviderInvocationResult unary() {
    return new ProviderAdapterPort.ProviderInvocationResult.Unary(
        new CanonicalResponse(
            "ok",
            List.of(),
            FinishReason.STOP,
            new CanonicalUsage(1, 1, 0, 0, 0, UsageClass.AUTHORITATIVE),
            ProviderMeta.empty()));
  }

  @Test
  void dispatchesToTheBoundAdapterByRouteRef() {
    final AdapterRegistry registry = new AdapterRegistry(Map.of("route/a", constant(unary())));
    final ProviderAdapterPort.ProviderInvocationResult result =
        registry.invoke(REQUEST, new RouteTarget(MODEL, "route/a"), BUDGET);
    assertThat(result).isInstanceOf(ProviderAdapterPort.ProviderInvocationResult.Unary.class);
  }

  @Test
  void unknownRouteFailsClosedWithoutGuess() {
    final AdapterRegistry registry = new AdapterRegistry(Map.of("route/a", constant(unary())));
    final ProviderAdapterPort.ProviderInvocationResult result =
        registry.invoke(REQUEST, new RouteTarget(MODEL, "route/missing"), BUDGET);
    final CanonicalError error =
        ((ProviderAdapterPort.ProviderInvocationResult.Failed) result).error();
    assertThat(error.category()).isEqualTo(ErrorCategory.UNKNOWN);
    assertThat(error.transientError()).isFalse();
  }

  @Test
  void rejectsNullOrBlankBindings() {
    assertThatThrownBy(() -> new AdapterRegistry(null)).isInstanceOf(NullPointerException.class);
    final Map<String, ProviderAdapterPort> blankKey = new java.util.HashMap<>();
    blankKey.put("  ", constant(unary()));
    assertThatThrownBy(() -> new AdapterRegistry(blankKey))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullInvokeArguments() {
    final AdapterRegistry registry = new AdapterRegistry(Map.of("route/a", constant(unary())));
    assertThatThrownBy(() -> registry.invoke(null, new RouteTarget(MODEL, "route/a"), BUDGET))
        .isInstanceOf(NullPointerException.class);
  }
}
