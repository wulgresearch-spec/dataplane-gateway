package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.canonical.capability.ProviderCapability;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Everything a provider declares about itself: who it is and which model-on-route combinations it
 * serves, with the capabilities of each.
 *
 * <p>This is a provider's entire contribution to the gateway's knowledge of it. There is no second
 * place where a provider is described, no runtime probe that fills gaps, and no gateway-side table
 * of per-vendor quirks. Adding a provider means writing one of these and a translator; it does not
 * mean editing the router, the governance engine, the pipeline or the composition root — which is
 * the property this whole abstraction exists to buy.
 *
 * <p><b>Declared, not discovered</b> (Doc 25 CAP-3). The descriptor is authored by whoever writes
 * the provider module and validated at startup against the operator's published capability
 * snapshot. It is never assembled by asking the provider's API what it can do.
 *
 * @param providerId the opaque provider identity, for operators and never for branching
 * @param routes the model-on-route declarations this provider serves
 */
public record ProviderDescriptor(ProviderId providerId, List<ProviderRoute> routes) {

  /** Validates the descriptor and rejects a duplicated route reference. */
  public ProviderDescriptor {
    Preconditions.requireNonNull(providerId, "providerId");
    Preconditions.requireNonNull(routes, "routes");
    final Set<String> seen = new HashSet<>();
    for (final ProviderRoute route : routes) {
      Preconditions.requireNonNull(route, "route");
      if (!seen.add(key(route))) {
        // Two declarations for the same model on the same route would need a precedence rule, and
        // any
        // such rule is a second place capability truth lives. Refusing is clearer than choosing.
        throw new IllegalArgumentException(
            "duplicate route declaration: " + key(route) + " for provider " + providerId);
      }
    }
    routes = List.copyOf(new ArrayList<>(routes));
  }

  private static String key(final ProviderRoute route) {
    return route.providerRouteRef() + "|" + route.canonicalModelId().value();
  }

  /**
   * The declaration for one model on one route.
   *
   * @param routeRef the opaque route reference
   * @param model the canonical model
   * @return the route declaration, or empty when this provider does not serve it
   */
  public Optional<ProviderRoute> route(final String routeRef, final CanonicalModelId model) {
    for (final ProviderRoute route : routes) {
      if (route.providerRouteRef().equals(routeRef) && route.canonicalModelId().equals(model)) {
        return Optional.of(route);
      }
    }
    return Optional.empty();
  }

  /**
   * Every route reference this provider answers on — what the registry indexes for dispatch.
   *
   * @return the route references
   */
  public Set<String> routeRefs() {
    final Set<String> refs = new HashSet<>();
    for (final ProviderRoute route : routes) {
      refs.add(route.providerRouteRef());
    }
    return Set.copyOf(refs);
  }

  /**
   * The union of everything this provider can do anywhere.
   *
   * <p>Useful for an operator overview and <b>wrong for routing</b>: it is the set that would send
   * a vision request to a text-only model on the same vendor. Routing asks {@link #route} for the
   * specific model's capabilities instead.
   *
   * @return the union of all route capabilities
   */
  public CapabilitySet aggregateCapabilities() {
    CapabilitySet union = CapabilitySet.none();
    for (final ProviderRoute route : routes) {
      union = union.union(route.capabilities());
    }
    return union;
  }

  /**
   * Whether any route of this provider offers a capability.
   *
   * @param capability the capability
   * @return {@code true} when at least one route declares it
   */
  public boolean offersAnywhere(final ProviderCapability capability) {
    return aggregateCapabilities().supports(capability);
  }
}
