package io.reliabilityai.gateway.dataplane.provider.application;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilitySnapshotPort;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderDescriptor;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderFault;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModule;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRegistration;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRoute;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderState;
import io.reliabilityai.gateway.dataplane.provider.domain.CapabilityNegotiation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Collects what each provider module declares and checks it against what the operator published.
 *
 * <p><b>Discovery here means enumerating declarations, not interrogating APIs.</b> Nothing in this
 * class calls a provider. Capabilities are authored by the control plane and consumed read-only
 * (Doc 25 CAP-1…CAP-6); a runtime probe would make a route's capabilities depend on when you asked
 * and would move authorship out of the registry. What is discovered is which modules this node was
 * built with and whether their declarations are consistent with the snapshot it was given.
 *
 * <p><b>The check worth having is over-claim.</b> A published snapshot granting a capability the
 * module does not declare means the router will select that route for work its adapter cannot do —
 * a failure that recurs on every matching request, in production, with the caller charged for it.
 * Finding it at startup converts that into a line in a report. The other faults are cheaper: an
 * under-claim or an unpublished route means an ability goes unused, which is wasteful rather than
 * wrong, so discovery records them and lets the provider serve.
 *
 * <p>Pure and side-effect free — it starts nothing. Starting is {@code ProviderLifecycle}'s job,
 * and keeping them apart means a node can validate its provider wiring without opening a
 * connection.
 */
public final class ProviderDiscovery {

  /**
   * What discovery found.
   *
   * @param registrations one entry per module, in declaration order, each already carrying its
   *     faults
   * @param faults every fault found, across all providers
   */
  public record DiscoveryReport(
      List<ProviderRegistration> registrations, List<ProviderFault> faults) {

    /** Validates the report. */
    public DiscoveryReport {
      registrations = registrations == null ? List.of() : List.copyOf(registrations);
      faults = faults == null ? List.of() : List.copyOf(faults);
    }

    /**
     * The providers that passed validation and may be started.
     *
     * @return the validated registrations
     */
    public List<ProviderRegistration> validated() {
      return registrations.stream().filter(r -> r.state() == ProviderState.VALIDATED).toList();
    }

    /**
     * Whether any provider was rejected.
     *
     * @return {@code true} when at least one fault is fatal
     */
    public boolean hasFatalFaults() {
      return faults.stream().anyMatch(ProviderFault::fatal);
    }
  }

  /**
   * Enumerates and validates the supplied modules.
   *
   * @param modules the provider modules this node was composed with
   * @param published the operator's capability snapshot, the authority on what routes may do
   * @return the discovery report
   */
  public DiscoveryReport discover(
      final List<ProviderModule> modules, final CapabilitySnapshotPort published) {
    Preconditions.requireNonNull(modules, "modules");
    Preconditions.requireNonNull(published, "published");

    final Map<ProviderId, List<ProviderFault>> faultsByProvider = new LinkedHashMap<>();
    final Map<ProviderId, ProviderDescriptor> descriptors = new LinkedHashMap<>();
    final Map<String, ProviderId> routeOwners = new HashMap<>();
    final List<ProviderFault> all = new ArrayList<>();

    for (final ProviderModule module : modules) {
      Preconditions.requireNonNull(module, "module");
      final ProviderDescriptor descriptor = module.descriptor();
      Preconditions.requireNonNull(descriptor, "descriptor");
      final ProviderId id = descriptor.providerId();
      final List<ProviderFault> faults =
          faultsByProvider.computeIfAbsent(id, key -> new ArrayList<>());

      if (descriptors.putIfAbsent(id, descriptor) != null) {
        faults.add(
            new ProviderFault(
                id,
                ProviderFault.NO_ROUTE,
                ProviderFault.Kind.DUPLICATE_PROVIDER,
                "two modules declare this provider id"));
        continue;
      }

      for (final ProviderRoute route : descriptor.routes()) {
        final ProviderId owner = routeOwners.putIfAbsent(route.providerRouteRef(), id);
        if (owner != null && !owner.equals(id)) {
          // Dispatch is keyed on the route reference, so a second claimant would shadow the first
          // silently — the request would go somewhere nobody intended.
          faults.add(
              new ProviderFault(
                  id,
                  route.providerRouteRef(),
                  ProviderFault.Kind.DUPLICATE_ROUTE,
                  "route reference already claimed by another provider"));
          continue;
        }
        faults.addAll(validateAgainstSnapshot(id, route, published));
      }
    }

    final List<ProviderRegistration> registrations = new ArrayList<>();
    for (final Map.Entry<ProviderId, ProviderDescriptor> entry : descriptors.entrySet()) {
      final List<ProviderFault> faults = faultsByProvider.getOrDefault(entry.getKey(), List.of());
      all.addAll(faults);
      final boolean fatal = faults.stream().anyMatch(ProviderFault::fatal);
      registrations.add(
          new ProviderRegistration(
              entry.getValue(),
              fatal ? ProviderState.FAILED : ProviderState.VALIDATED,
              Optional.empty(),
              Optional.empty(),
              faults));
    }
    // Providers rejected as duplicates never made it into the descriptor map; surface their faults
    // too.
    for (final Map.Entry<ProviderId, List<ProviderFault>> entry : faultsByProvider.entrySet()) {
      if (!descriptors.containsKey(entry.getKey())) {
        all.addAll(entry.getValue());
      }
    }
    return new DiscoveryReport(registrations, all);
  }

  /**
   * Compares one declared route with the published snapshot.
   *
   * <p>The snapshot is the authority (CAP-1), so the comparison is asymmetric on purpose: what the
   * snapshot grants and the module cannot do is dangerous, and what the module can do and the
   * snapshot withholds is merely unused.
   */
  private static List<ProviderFault> validateAgainstSnapshot(
      final ProviderId id, final ProviderRoute route, final CapabilitySnapshotPort published) {
    final Optional<CapabilityMapping> mapping =
        published.mappingFor(new RouteTarget(route.canonicalModelId(), route.providerRouteRef()));
    if (mapping.isEmpty()) {
      return List.of(
          new ProviderFault(
              id,
              route.providerRouteRef(),
              ProviderFault.Kind.ROUTE_NOT_PUBLISHED,
              "declared route absent from the published capability snapshot"));
    }

    final CapabilitySet declared = route.capabilities();
    final CapabilitySet snapshot = CapabilitySet.fromTokens(mapping.orElseThrow().capabilities());
    final List<ProviderFault> faults = new ArrayList<>(2);

    final CapabilityNegotiation overclaim = CapabilityNegotiation.negotiate(snapshot, declared);
    if (!overclaim.satisfied()) {
      faults.add(
          new ProviderFault(
              id,
              route.providerRouteRef(),
              ProviderFault.Kind.CAPABILITY_OVERCLAIM,
              "snapshot grants capabilities the module does not declare: "
                  + overclaim.missing().tokens()));
    }
    final CapabilityNegotiation underclaim = CapabilityNegotiation.negotiate(declared, snapshot);
    if (!underclaim.satisfied()) {
      faults.add(
          new ProviderFault(
              id,
              route.providerRouteRef(),
              ProviderFault.Kind.CAPABILITY_UNDERCLAIM,
              "module declares capabilities the snapshot omits: " + underclaim.missing().tokens()));
    }
    return faults;
  }
}
