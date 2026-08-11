package io.reliabilityai.gateway.dataplane.provider.application;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRegistration;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRoute;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The single place the gateway knows which providers exist, what they can do, and where to send a
 * request.
 *
 * <p>It is itself a {@link ProviderAdapterPort}, so Reliability holds one interface and never
 * learns that more than one provider exists — adding a provider is a registration, not a pipeline
 * change (Doc 25 §7.1, PA-D2). Dispatch is a map lookup on the opaque route reference; there is no
 * provider-name comparison anywhere in this class, and there is nowhere one could be added without
 * a reviewer noticing.
 *
 * <p><b>Thread model.</b> One {@link AtomicReference} to an immutable view holding both the
 * registrations and the derived dispatch table. A request does a single volatile read; a lifecycle
 * change publishes a whole new view. Because the two are swapped together, no request can observe a
 * dispatch table that disagrees with the states it was derived from — a provider is either
 * dispatchable with its adapter present, or absent from the table entirely.
 *
 * <p>An unknown or non-dispatchable route fails closed with a canonical error. That is deliberately
 * the same answer for "no such route" and "that provider is disabled": telling a caller which of
 * the two it was would leak the node's provider inventory, and neither is recoverable by retrying
 * elsewhere in a way the caller controls.
 */
public final class ProviderRegistry implements ProviderAdapterPort {

  /** An immutable view: the registrations and the dispatch table derived from them, in lockstep. */
  private record View(
      Map<ProviderId, ProviderRegistration> byProvider,
      Map<String, ProviderAdapterPort> dispatch,
      Map<String, ProviderRoute> routes) {}

  private final AtomicReference<View> view;

  /**
   * Creates the registry from an initial set of registrations.
   *
   * @param registrations the registrations, in declaration order
   */
  public ProviderRegistry(final List<ProviderRegistration> registrations) {
    Preconditions.requireNonNull(registrations, "registrations");
    this.view = new AtomicReference<>(viewOf(registrations));
  }

  private static View viewOf(final List<ProviderRegistration> registrations) {
    final Map<ProviderId, ProviderRegistration> byProvider = new LinkedHashMap<>();
    final Map<String, ProviderAdapterPort> dispatch = new LinkedHashMap<>();
    final Map<String, ProviderRoute> routes = new LinkedHashMap<>();
    for (final ProviderRegistration registration : registrations) {
      Preconditions.requireNonNull(registration, "registration");
      byProvider.put(registration.providerId(), registration);
      for (final ProviderRoute route : registration.descriptor().routes()) {
        routes.put(routeKey(route.providerRouteRef(), route.canonicalModelId().value()), route);
      }
      if (!registration.dispatchable()) {
        continue;
      }
      final ProviderAdapterPort adapter = registration.instance().orElseThrow().adapter();
      for (final String routeRef : registration.descriptor().routeRefs()) {
        dispatch.put(routeRef, adapter);
      }
    }
    return new View(Map.copyOf(byProvider), Map.copyOf(dispatch), Map.copyOf(routes));
  }

  private static String routeKey(final String routeRef, final String model) {
    return routeRef + "|" + model;
  }

  @Override
  public ProviderInvocationResult invoke(
      final CanonicalRequest request, final RouteTarget routeTarget, final AttemptBudget budget) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(routeTarget, "routeTarget");
    Preconditions.requireNonNull(budget, "budget");
    final ProviderAdapterPort adapter = view.get().dispatch().get(routeTarget.providerRouteRef());
    if (adapter == null) {
      return new ProviderInvocationResult.Failed(
          new CanonicalError(ErrorCategory.UNKNOWN, Boolean.FALSE, "no_adapter_for_route", false));
    }
    return adapter.invoke(request, routeTarget, budget);
  }

  /**
   * What a route can do, as declared.
   *
   * <p>The capability question the whole platform is built around, answerable without knowing or
   * caring which provider serves the route.
   *
   * @param routeTarget the route
   * @return the declared capabilities, or empty when the route is unknown
   */
  public Optional<CapabilitySet> capabilitiesOf(final RouteTarget routeTarget) {
    if (routeTarget == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(
            view.get()
                .routes()
                .get(
                    routeKey(
                        routeTarget.providerRouteRef(), routeTarget.canonicalModelId().value())))
        .map(ProviderRoute::capabilities);
  }

  /**
   * Every declared route, keyed by route reference and canonical model.
   *
   * @return the declared routes
   */
  public List<ProviderRoute> routes() {
    return List.copyOf(view.get().routes().values());
  }

  /**
   * The current registrations, in declaration order.
   *
   * @return the registrations
   */
  public List<ProviderRegistration> registrations() {
    return List.copyOf(view.get().byProvider().values());
  }

  /**
   * One provider's registration.
   *
   * @param providerId the provider
   * @return the registration, or empty when unregistered
   */
  public Optional<ProviderRegistration> registration(final ProviderId providerId) {
    return Optional.ofNullable(view.get().byProvider().get(providerId));
  }

  /**
   * How many providers can currently serve traffic.
   *
   * @return the dispatchable provider count
   */
  public long dispatchableCount() {
    return view.get().byProvider().values().stream()
        .filter(ProviderRegistration::dispatchable)
        .count();
  }

  /**
   * Whether any provider can serve traffic — what the activation gate asks before binding ADAPTER.
   *
   * @return {@code true} when at least one provider is dispatchable
   */
  public boolean hasDispatchableProvider() {
    return dispatchableCount() > 0L;
  }

  /**
   * Replaces one provider's registration and republishes the dispatch table.
   *
   * <p>A compare-and-set loop rather than a lock: lifecycle changes are rare and readers are
   * constant, so the cost belongs on the writer. Losing the race means another lifecycle change
   * landed first, and retrying against the newer view is the correct resolution — the alternative,
   * overwriting it, would silently discard a state transition.
   *
   * @param updated the new registration
   * @return {@code true} when applied, {@code false} when the provider is not registered
   */
  public boolean update(final ProviderRegistration updated) {
    Preconditions.requireNonNull(updated, "updated");
    while (true) {
      final View current = view.get();
      if (!current.byProvider().containsKey(updated.providerId())) {
        return false;
      }
      final List<ProviderRegistration> next = new ArrayList<>(current.byProvider().size());
      for (final ProviderRegistration existing : current.byProvider().values()) {
        next.add(existing.providerId().equals(updated.providerId()) ? updated : existing);
      }
      if (view.compareAndSet(current, viewOf(next))) {
        return true;
      }
    }
  }
}
