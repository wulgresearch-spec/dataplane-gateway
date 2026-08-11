package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Optional;

/**
 * One provider as the registry currently sees it: what it declared, where it is in its lifecycle,
 * what went wrong, and — once started — the instance serving it.
 *
 * <p>Immutable. A state change replaces the registration rather than mutating it, which is what
 * lets the registry publish a whole new view with one atomic reference swap and lets the request
 * path read it without a lock.
 *
 * @param descriptor what the provider declared about itself
 * @param state where it is in its lifecycle
 * @param instance the started provider, absent until it starts and after it is disabled
 * @param health the last liveness verdict, absent until first probed
 * @param faults validation disagreements found for this provider
 */
public record ProviderRegistration(
    ProviderDescriptor descriptor,
    ProviderState state,
    Optional<ProviderInstance> instance,
    Optional<ProviderHealth> health,
    List<ProviderFault> faults) {

  /** Validates the registration. */
  public ProviderRegistration {
    Preconditions.requireNonNull(descriptor, "descriptor");
    Preconditions.requireNonNull(state, "state");
    Preconditions.requireNonNull(instance, "instance");
    Preconditions.requireNonNull(health, "health");
    faults = faults == null ? List.of() : List.copyOf(faults);
  }

  /**
   * The provider's identity.
   *
   * @return the provider id
   */
  public ProviderId providerId() {
    return descriptor.providerId();
  }

  /**
   * Whether requests may currently be dispatched here.
   *
   * <p>Requires both a dispatchable state and a started instance. The two can disagree during
   * startup, and dispatching to a state without an instance would be a null adapter.
   *
   * @return {@code true} when this provider can serve traffic
   */
  public boolean dispatchable() {
    return state.dispatchable() && instance.isPresent();
  }

  /**
   * Whether any fatal fault was recorded.
   *
   * @return {@code true} when a fault prevents dispatch
   */
  public boolean hasFatalFault() {
    return faults.stream().anyMatch(ProviderFault::fatal);
  }

  /**
   * A copy in a new state.
   *
   * @param next the new state
   * @return the updated registration
   */
  public ProviderRegistration withState(final ProviderState next) {
    return new ProviderRegistration(descriptor, next, instance, health, faults);
  }

  /**
   * A copy carrying a started instance, moved to {@link ProviderState#READY}.
   *
   * @param started the started provider
   * @return the updated registration
   */
  public ProviderRegistration started(final ProviderInstance started) {
    return new ProviderRegistration(
        descriptor, ProviderState.READY, Optional.of(started), health, faults);
  }

  /**
   * A copy carrying a fresh health verdict, moving between ready and degraded accordingly.
   *
   * <p>Only moves between those two states. A failed or disabled provider stays where it is: a
   * probe cannot resurrect a provider whose declaration was rejected, and letting it would route
   * traffic to an adapter validation refused.
   *
   * @param verdict the probe verdict
   * @return the updated registration
   */
  public ProviderRegistration withHealth(final ProviderHealth verdict) {
    Preconditions.requireNonNull(verdict, "verdict");
    final ProviderState next =
        switch (state) {
          case READY, DEGRADED -> verdict.healthy() ? ProviderState.READY : ProviderState.DEGRADED;
          default -> state;
        };
    return new ProviderRegistration(descriptor, next, instance, Optional.of(verdict), faults);
  }

  /**
   * A copy carrying additional faults, moved to {@link ProviderState#FAILED} when any is fatal.
   *
   * @param additional the faults to record
   * @return the updated registration
   */
  public ProviderRegistration withFaults(final List<ProviderFault> additional) {
    Preconditions.requireNonNull(additional, "additional");
    final List<ProviderFault> merged = new java.util.ArrayList<>(faults);
    merged.addAll(additional);
    final boolean fatal = merged.stream().anyMatch(ProviderFault::fatal);
    return new ProviderRegistration(
        descriptor, fatal ? ProviderState.FAILED : state, instance, health, merged);
  }
}
