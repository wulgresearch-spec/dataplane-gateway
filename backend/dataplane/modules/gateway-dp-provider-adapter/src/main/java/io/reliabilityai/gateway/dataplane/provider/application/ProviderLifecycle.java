package io.reliabilityai.gateway.dataplane.provider.application;

import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderFault;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderHealth;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderInstance;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModule;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRegistration;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRuntimeContext;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderState;
import io.reliabilityai.gateway.ports.AttemptBudget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Moves providers through their lifecycle: start the validated ones, probe them, disable them, and
 * shut them down.
 *
 * <p>Separate from {@link ProviderRegistry} because the registry answers questions on the request
 * path and this does not. Everything here runs at startup, on an operator action, or on a
 * background probe; keeping the transition logic out of the registry keeps the hot path to a
 * volatile read and a map lookup.
 *
 * <p><b>One provider failing to start does not stop the others.</b> A node with three providers
 * where one has a bad endpoint should serve the other two, not refuse to come up — the alternative
 * turns a single misconfigured provider into a total outage, which is the coupling this gateway
 * exists to prevent. The failure is recorded as a fatal fault, that provider is excluded from
 * dispatch, and the node reports honestly what it is serving.
 *
 * <p>Nothing here throws to its caller. Startup, probing and shutdown all record outcomes instead,
 * because a provider misbehaving during lifecycle must not become an exception the composition root
 * has to decide about.
 */
public final class ProviderLifecycle {

  private final ProviderRegistry registry;
  private final Map<ProviderId, ProviderModule> modules;

  /**
   * Creates the lifecycle service.
   *
   * @param registry the registry whose registrations it transitions
   * @param modules the modules backing those registrations, by provider id
   */
  public ProviderLifecycle(
      final ProviderRegistry registry, final Map<ProviderId, ProviderModule> modules) {
    this.registry = Preconditions.requireNonNull(registry, "registry");
    this.modules = Map.copyOf(Preconditions.requireNonNull(modules, "modules"));
  }

  /**
   * Builds the lifecycle service and its registry from a discovery report.
   *
   * @param report what discovery found
   * @param modules the modules that were discovered
   * @return the lifecycle service, with a registry holding every registration
   */
  public static ProviderLifecycle from(
      final ProviderDiscovery.DiscoveryReport report, final List<ProviderModule> modules) {
    Preconditions.requireNonNull(report, "report");
    Preconditions.requireNonNull(modules, "modules");
    final Map<ProviderId, ProviderModule> byId = new LinkedHashMap<>();
    for (final ProviderModule module : modules) {
      byId.putIfAbsent(module.descriptor().providerId(), module);
    }
    return new ProviderLifecycle(new ProviderRegistry(report.registrations()), byId);
  }

  /**
   * The registry these transitions publish into.
   *
   * @return the provider registry
   */
  public ProviderRegistry registry() {
    return registry;
  }

  /**
   * Starts every validated provider.
   *
   * @param context the neutral collaborators handed to each module
   * @return the providers that failed to start, in declaration order
   */
  public List<ProviderFault> startAll(final ProviderRuntimeContext context) {
    Preconditions.requireNonNull(context, "context");
    final List<ProviderFault> failures = new ArrayList<>();
    for (final ProviderRegistration registration : registry.registrations()) {
      if (registration.state() != ProviderState.VALIDATED) {
        continue;
      }
      startOne(registration, context).ifPresent(failures::add);
    }
    return List.copyOf(failures);
  }

  private Optional<ProviderFault> startOne(
      final ProviderRegistration registration, final ProviderRuntimeContext context) {
    final ProviderModule module = modules.get(registration.providerId());
    if (module == null) {
      final ProviderFault fault =
          new ProviderFault(
              registration.providerId(),
              ProviderFault.NO_ROUTE,
              ProviderFault.Kind.START_FAILED,
              "no module registered for this provider");
      registry.update(registration.withFaults(List.of(fault)));
      return Optional.of(fault);
    }
    try {
      final ProviderInstance instance = module.start(context);
      Preconditions.requireNonNull(instance, "instance");
      registry.update(registration.started(instance));
      return Optional.empty();
    } catch (final RuntimeException startFailure) {
      // Recorded, not propagated: one bad provider must not prevent the node serving the others.
      final ProviderFault fault =
          new ProviderFault(
              registration.providerId(),
              ProviderFault.NO_ROUTE,
              ProviderFault.Kind.START_FAILED,
              "module threw while starting");
      registry.update(registration.withFaults(List.of(fault)));
      return Optional.of(fault);
    }
  }

  /**
   * Probes one provider and records the verdict.
   *
   * <p>A provider with no probe is recorded as not-probed rather than assumed healthy. Assuming
   * health would put a provider that has never answered into the same bucket as one that just did.
   *
   * @param providerId the provider to probe
   * @param credential a live credential lease
   * @param budget the probe's transport budget
   * @return the verdict, or empty when the provider is not registered
   */
  public Optional<ProviderHealth> probe(
      final ProviderId providerId, final CredentialLease credential, final AttemptBudget budget) {
    Preconditions.requireNonNull(providerId, "providerId");
    final Optional<ProviderRegistration> found = registry.registration(providerId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    final ProviderRegistration registration = found.orElseThrow();
    final Optional<io.reliabilityai.gateway.dataplane.provider.api.ProviderProbe> probe =
        registration.instance().flatMap(ProviderInstance::probe);
    final ProviderHealth verdict;
    if (probe.isEmpty()) {
      verdict = ProviderHealth.notProbed(providerId);
    } else {
      verdict = runProbe(providerId, probe.orElseThrow(), credential, budget);
    }
    registry.update(registration.withHealth(verdict));
    return Optional.of(verdict);
  }

  private static ProviderHealth runProbe(
      final ProviderId providerId,
      final io.reliabilityai.gateway.dataplane.provider.api.ProviderProbe probe,
      final CredentialLease credential,
      final AttemptBudget budget) {
    try {
      final ProviderHealth verdict = probe.probe(credential, budget);
      return verdict == null
          ? ProviderHealth.unhealthy(providerId, "probe-returned-nothing")
          : verdict;
    } catch (final RuntimeException probeFailure) {
      return ProviderHealth.unhealthy(providerId, "probe-failed");
    }
  }

  /**
   * Withdraws a provider from dispatch without stopping the node.
   *
   * <p>The operator control this platform previously lacked: taking one provider out of rotation
   * meant a config change and a restart, which is a poor answer during an incident.
   *
   * @param providerId the provider to withdraw
   * @return {@code true} when the provider was registered and is now disabled
   */
  public boolean disable(final ProviderId providerId) {
    Preconditions.requireNonNull(providerId, "providerId");
    return registry
        .registration(providerId)
        .map(registration -> registry.update(registration.withState(ProviderState.DISABLED)))
        .orElse(false);
  }

  /**
   * Returns a disabled provider to service.
   *
   * <p>Only a provider that actually started can be re-enabled. One that failed validation stays
   * failed: its declaration disagreed with the published snapshot, and re-enabling it would route
   * traffic to an adapter that cannot serve it.
   *
   * @param providerId the provider to restore
   * @return {@code true} when the provider was disabled and has now returned to service
   */
  public boolean enable(final ProviderId providerId) {
    Preconditions.requireNonNull(providerId, "providerId");
    return registry
        .registration(providerId)
        .filter(registration -> registration.state() == ProviderState.DISABLED)
        .filter(registration -> registration.instance().isPresent())
        .map(registration -> registry.update(registration.withState(ProviderState.READY)))
        .orElse(false);
  }

  /**
   * Closes every started provider, in reverse registration order.
   *
   * <p>Reverse order because a provider registered later may have been given something an earlier
   * one owns. A provider that throws on close does not stop the rest closing — shutdown must reach
   * every connection pool and, beyond this class, the secret zeroization that follows it.
   */
  public void stopAll() {
    final List<ProviderRegistration> registrations = new ArrayList<>(registry.registrations());
    java.util.Collections.reverse(registrations);
    for (final ProviderRegistration registration : registrations) {
      registration
          .instance()
          .ifPresent(
              instance -> {
                try {
                  instance.close();
                } catch (final Exception closeFailure) {
                  // deliberately ignored — see method javadoc
                }
              });
      registry.update(
          new ProviderRegistration(
              registration.descriptor(),
              ProviderState.DISABLED,
              Optional.empty(),
              registration.health(),
              registration.faults()));
    }
  }
}
