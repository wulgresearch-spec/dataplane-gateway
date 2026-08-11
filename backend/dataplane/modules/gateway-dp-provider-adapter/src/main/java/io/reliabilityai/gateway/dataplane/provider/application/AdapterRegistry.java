package io.reliabilityai.gateway.dataplane.provider.application;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import java.util.Map;

/**
 * Data-driven adapter dispatch (Doc 25 §7.1, PA-D2) — a {@code providerRouteRef → adapter} registry
 * lookup, <b>never</b> a provider-name {@code switch}/{@code if} (AD-007, PA-A4). It is itself a
 * {@link ProviderAdapterPort}, so Reliability (Doc 20) holds only the interface and never names a
 * provider; adding a provider registers a new binding with <b>zero pipeline diff</b> (Doc 25 §7.1).
 * An unknown route ⇒ <b>fail closed</b> (no such adapter, Doc 25 §26/PA-D2) — never a guess.
 * Immutable bindings; virtual-thread-safe.
 */
public final class AdapterRegistry implements ProviderAdapterPort {

  private final Map<String, ProviderAdapterPort> byRoute;

  /**
   * Creates the registry from immutable route bindings (Doc 25 §7.1).
   *
   * @param bindings {@code providerRouteRef → adapter} (defensively copied; no null key/value)
   */
  public AdapterRegistry(final Map<String, ProviderAdapterPort> bindings) {
    Preconditions.requireNonNull(bindings, "bindings");
    bindings.forEach(
        (route, adapter) -> {
          Preconditions.requireNonBlank(route, "route");
          Preconditions.requireNonNull(adapter, "adapter");
        });
    this.byRoute = Map.copyOf(bindings);
  }

  @Override
  public ProviderInvocationResult invoke(
      final CanonicalRequest request, final RouteTarget routeTarget, final AttemptBudget budget) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(routeTarget, "routeTarget");
    Preconditions.requireNonNull(budget, "budget");
    final ProviderAdapterPort adapter = byRoute.get(routeTarget.providerRouteRef());
    if (adapter == null) {
      // Fail closed: no adapter is registered for this route (Doc 25 §26/PA-D2). No provider guess.
      return new ProviderInvocationResult.Failed(
          new CanonicalError(ErrorCategory.UNKNOWN, Boolean.FALSE, "no_adapter_for_route", false));
    }
    return adapter.invoke(request, routeTarget, budget);
  }
}
