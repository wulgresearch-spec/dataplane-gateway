package io.reliabilityai.gateway.dataplane.embedding.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingProviderPort;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.ports.ClockPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Which provider serves which model, and which of them is currently worth calling (AD-030 §4.2).
 *
 * <p>Registration is the <em>only</em> place a vendor enters the system. Everything above reads
 * this registry by neutral model id and never learns what answered.
 *
 * <p>Selection is deterministic: healthy before degraded, then by provider id. Deterministic
 * matters more than clever here — an embedding produced by a different provider is a vector in a
 * different space, so a request that silently switched providers would write a record no later
 * query can match. {@code CanonicalEmbedding} records which provider produced it precisely so that
 * mismatch is detectable rather than mysterious.
 */
public final class EmbeddingProviderRegistry {

  private final Map<String, Registration> byId = new ConcurrentHashMap<>();

  private final EmbeddingFailurePolicy failurePolicy;

  private final ClockPort clock;

  /**
   * Creates an empty registry.
   *
   * @param failurePolicy how failures move a provider between health states
   * @param clock the time source
   */
  public EmbeddingProviderRegistry(
      final EmbeddingFailurePolicy failurePolicy, final ClockPort clock) {
    this.failurePolicy = Preconditions.requireNonNull(failurePolicy, "failurePolicy");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  /**
   * Registers a provider.
   *
   * @param provider the adapter
   */
  public void register(final EmbeddingProviderPort provider) {
    Preconditions.requireNonNull(provider, "provider");
    byId.put(provider.id().value(), new Registration(provider, clock.now()));
  }

  /**
   * Removes a provider.
   *
   * @param id which provider
   */
  public void deregister(final ProviderId id) {
    byId.remove(Preconditions.requireNonNull(id, "id").value());
  }

  /**
   * Chooses a provider for a model.
   *
   * <p>An unavailable provider is still returned when it is the only one serving the model.
   * Refusing outright would turn a transient outage into a permanent one, and the caller has a
   * retry policy for exactly this. With more than one candidate, an unavailable provider is
   * skipped.
   *
   * @param modelId the neutral model id
   * @return the chosen provider and the model it serves, or empty when nothing serves it
   */
  public Optional<Selection> select(final String modelId) {
    Preconditions.requireNonBlank(modelId, "modelId");
    final List<Registration> candidates = new ArrayList<>();
    for (final Registration registration : byId.values()) {
      if (registration.provider.capability().model(modelId).isPresent()) {
        candidates.add(registration);
      }
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    candidates.sort(
        Comparator.comparingInt((Registration r) -> r.health.get().state().ordinal())
            .thenComparing(r -> r.provider.id().value()));
    final Registration chosen = candidates.get(0);
    return chosen
        .provider
        .capability()
        .model(modelId)
        .map(model -> new Selection(chosen.provider, model));
  }

  /**
   * The current health of one provider.
   *
   * @param id which provider
   * @return its health, or empty when not registered
   */
  public Optional<EmbeddingHealth> health(final ProviderId id) {
    final Registration registration = byId.get(id.value());
    return registration == null ? Optional.empty() : Optional.of(registration.health.get());
  }

  /**
   * Records that a provider answered.
   *
   * @param id which provider
   */
  public void recordSuccess(final ProviderId id) {
    final Registration registration = byId.get(id.value());
    if (registration != null) {
      registration.health.set(failurePolicy.onSuccess(clock.now()));
    }
  }

  /**
   * Records that a provider failed.
   *
   * @param id which provider
   * @param reason why
   */
  public void recordFailure(final ProviderId id, final EmbeddingFailure reason) {
    final Registration registration = byId.get(id.value());
    if (registration != null) {
      registration.health.updateAndGet(current -> failurePolicy.onFailure(current, reason));
    }
  }

  /**
   * How many providers are registered.
   *
   * @return the provider count
   */
  public int size() {
    return byId.size();
  }

  /**
   * A chosen provider and the model it will serve.
   *
   * @param provider the adapter
   * @param model the model definition
   */
  public record Selection(EmbeddingProviderPort provider, EmbeddingModel model) {}

  /** A registered provider and its observed health. */
  private static final class Registration {

    private final EmbeddingProviderPort provider;

    private final AtomicReference<EmbeddingHealth> health;

    private Registration(final EmbeddingProviderPort provider, final java.time.Instant at) {
      this.provider = provider;
      this.health = new AtomicReference<>(EmbeddingHealth.healthy(at));
    }
  }
}
