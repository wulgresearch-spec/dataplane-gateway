package io.reliabilityai.gateway.dataplane.embedding.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingAuditPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingGovernancePort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingMetricsPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportException;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingCostEstimator;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingNormalizer;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingRetryPolicy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cache, then budget, then batch, then provider, then normalize (AD-030 §6).
 *
 * <p>The order is the design. Each stage exists to stop work reaching the next one.
 *
 * <ol>
 *   <li><b>Cache</b> first, because the cheapest provider call is the one not made — and because a
 *       budget check on work that will be served from memory would refuse spend that was never
 *       going to happen.
 *   <li><b>Governance</b> next, on the misses only, and <em>before</em> any call. Asking permission
 *       after the money is gone is reporting, not governance.
 *   <li><b>Split</b> into provider-sized pieces.
 *   <li><b>Attempt</b> with retry, one piece at a time.
 *   <li><b>Normalize</b> and check width, so nothing above ever sees a provider-shaped vector.
 * </ol>
 *
 * <p>Outcomes come back aligned to the request, whatever route each input took. A caller cannot
 * tell a cache hit from a fresh embedding except by the usage figures, which is the point.
 */
public final class EmbeddingPipeline {

  private final EmbeddingProviderRegistry registry;

  private final EmbeddingCache cache;

  private final EmbeddingGovernancePort governance;

  private final EmbeddingRetryPolicy retryPolicy;

  private final EmbeddingMetricsPort metrics;

  private final EmbeddingAuditPort audit;

  /**
   * Creates a pipeline.
   *
   * @param registry which providers serve which models
   * @param cache the embedding cache
   * @param governance the spend decision
   * @param retryPolicy when to try again
   * @param metrics where events are reported
   * @param audit where spend is recorded
   */
  public EmbeddingPipeline(
      final EmbeddingProviderRegistry registry,
      final EmbeddingCache cache,
      final EmbeddingGovernancePort governance,
      final EmbeddingRetryPolicy retryPolicy,
      final EmbeddingMetricsPort metrics,
      final EmbeddingAuditPort audit) {
    this.registry = Preconditions.requireNonNull(registry, "registry");
    this.cache = Preconditions.requireNonNull(cache, "cache");
    this.governance = Preconditions.requireNonNull(governance, "governance");
    this.retryPolicy = Preconditions.requireNonNull(retryPolicy, "retryPolicy");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.audit = Preconditions.requireNonNull(audit, "audit");
  }

  /**
   * Embeds a request.
   *
   * @param request the inputs
   * @return one outcome per input, in request order
   */
  public EmbeddingResponse embed(final EmbeddingRequest request) {
    Preconditions.requireNonNull(request, "request");
    final long started = System.nanoTime();
    final EmbeddingResponse.Outcome[] outcomes =
        new EmbeddingResponse.Outcome[request.texts().size()];

    final Optional<EmbeddingProviderRegistry.Selection> selection =
        registry.select(request.modelId());
    if (selection.isEmpty()) {
      return refuseAll(
          request,
          outcomes,
          EmbeddingFailure.REJECTED,
          "no provider serves model " + request.modelId(),
          started);
    }
    final EmbeddingProviderRegistry.Selection chosen = selection.get();
    final EmbeddingModel model = chosen.model();

    // 1. Cache, positive and negative.
    final List<Integer> misses = new ArrayList<>();
    final Map<Integer, String> keys = new HashMap<>();
    for (int i = 0; i < request.texts().size(); i++) {
      // Keyed against the provider that would actually serve this request, not just the neutral
      // model
      // id. Selection happens above, so the provider is already known here; without it a cached
      // vector
      // outlives the provider that produced it (AD-030 §6.1).
      final String key =
          EmbeddingCache.keyFor(
              request.tenant(), model, chosen.provider().id(), request.texts().get(i));
      keys.put(i, key);
      final Optional<EmbeddingFailure> known = cache.getNegative(key);
      if (known.isPresent()) {
        outcomes[i] =
            new EmbeddingResponse.Outcome.Failed(
                known.get(), "known-unembeddable input, not resent");
        continue;
      }
      final Optional<CanonicalEmbedding> hit = cache.get(key);
      if (hit.isPresent()) {
        outcomes[i] = new EmbeddingResponse.Outcome.Embedded(hit.get());
      } else {
        misses.add(i);
      }
    }
    if (misses.isEmpty()) {
      metrics.requestCompleted(request.size(), System.nanoTime() - started);
      return new EmbeddingResponse(List.of(outcomes), EmbeddingUsage.FREE);
    }

    // 2. Budget, on the misses only, before any call.
    final List<String> missTexts = misses.stream().map(i -> request.texts().get(i)).toList();
    final long estimatedTokens = EmbeddingCostEstimator.estimateTokens(missTexts);
    final long estimatedCost = EmbeddingCostEstimator.costMicros(estimatedTokens, model);
    if (governance.admitSpend(
            request.tenant(), request.modelId(), misses.size(), estimatedTokens, estimatedCost)
        == EmbeddingGovernancePort.Decision.DENY) {
      for (final int index : misses) {
        outcomes[index] =
            new EmbeddingResponse.Outcome.Failed(
                EmbeddingFailure.BUDGET_EXCEEDED, "governance refused the estimated spend");
      }
      metrics.failed(EmbeddingFailure.BUDGET_EXCEEDED, misses.size());
      audit.refused(
          request.tenant(), request.modelId(), EmbeddingFailure.BUDGET_EXCEEDED, misses.size());
      metrics.requestCompleted(request.size(), System.nanoTime() - started);
      return new EmbeddingResponse(List.of(outcomes), EmbeddingUsage.FREE);
    }

    // 3-5. Split, attempt, normalize.
    EmbeddingUsage usage = EmbeddingUsage.FREE;
    int missCursor = 0;
    int succeeded = 0;
    final Map<EmbeddingFailure, Integer> refusals = new EnumMap<>(EmbeddingFailure.class);
    final EmbeddingRequest missRequest =
        new EmbeddingRequest(request.tenant(), request.modelId(), missTexts, request.purpose());
    for (final EmbeddingRequest piece :
        EmbeddingBatcher.split(missRequest, chosen.provider().capability())) {
      final EmbeddingResponse pieceResponse = attempt(chosen, piece, model);
      usage = usage.plus(pieceResponse.usage());
      for (int i = 0; i < piece.size(); i++) {
        final int original = misses.get(missCursor++);
        final EmbeddingResponse.Outcome outcome = pieceResponse.outcomes().get(i);
        outcomes[original] = outcome;
        if (outcome instanceof EmbeddingResponse.Outcome.Embedded embedded) {
          cache.put(keys.get(original), embedded.embedding());
          succeeded++;
        } else if (outcome instanceof EmbeddingResponse.Outcome.Failed failed) {
          cache.putNegative(keys.get(original), failed.reason());
          metrics.failed(failed.reason(), 1);
          refusals.merge(failed.reason(), 1, Integer::sum);
        }
      }
    }

    metrics.cost(usage.costMicros(), usage.inputTokens());
    // Audited by what actually happened, not by what was attempted. Reporting every miss as
    // embedded
    // would tell the compliance record that a batch of sixty-four succeeded when the provider was
    // down and none of them did — and the audit trail is the one place that error is never caught,
    // because nothing downstream re-derives it.
    if (succeeded > 0) {
      audit.embedded(request.tenant(), chosen.provider().id(), request.modelId(), succeeded, usage);
    }
    for (final Map.Entry<EmbeddingFailure, Integer> refusal : refusals.entrySet()) {
      audit.refused(request.tenant(), request.modelId(), refusal.getKey(), refusal.getValue());
    }
    metrics.requestCompleted(request.size(), System.nanoTime() - started);
    return new EmbeddingResponse(List.of(outcomes), usage);
  }

  /**
   * Performs one piece with retry.
   *
   * @param chosen the provider and model
   * @param piece the batch, already within provider limits
   * @param model the model definition
   * @return the outcomes for this piece
   */
  private EmbeddingResponse attempt(
      final EmbeddingProviderRegistry.Selection chosen,
      final EmbeddingRequest piece,
      final EmbeddingModel model) {
    int attempts = 0;
    EmbeddingFailure lastReason = EmbeddingFailure.INTERNAL;
    String lastDetail = "no attempt was made";
    while (true) {
      attempts++;
      final long started = System.nanoTime();
      try {
        final EmbeddingResponse raw = chosen.provider().embed(piece);
        metrics.providerCall(
            chosen.provider().id(), piece.size(), System.nanoTime() - started, true);
        registry.recordSuccess(chosen.provider().id());
        return conform(raw, chosen, model);
      } catch (final EmbeddingTransportException failure) {
        metrics.providerCall(
            chosen.provider().id(), piece.size(), System.nanoTime() - started, false);
        registry.recordFailure(chosen.provider().id(), failure.reason());
        lastReason = failure.reason();
        lastDetail = failure.getMessage() == null ? failure.reason().name() : failure.getMessage();
        if (!retryPolicy.shouldRetry(lastReason, attempts)) {
          break;
        }
        metrics.retry(chosen.provider().id(), lastReason, attempts);
        sleep(retryPolicy.delayMillis(attempts));
      }
    }
    final List<EmbeddingResponse.Outcome> exhausted = new ArrayList<>(piece.size());
    for (int i = 0; i < piece.size(); i++) {
      exhausted.add(new EmbeddingResponse.Outcome.Failed(lastReason, lastDetail));
    }
    return new EmbeddingResponse(exhausted, new EmbeddingUsage(0L, attempts, 0L));
  }

  /**
   * Normalizes and width-checks everything a provider returned.
   *
   * @param raw what the adapter produced
   * @param chosen the provider and model
   * @param model the model definition
   * @return outcomes carrying canonical vectors
   */
  private EmbeddingResponse conform(
      final EmbeddingResponse raw,
      final EmbeddingProviderRegistry.Selection chosen,
      final EmbeddingModel model) {
    final boolean reduction = chosen.provider().capability().supportsDimensionReduction();
    final List<EmbeddingResponse.Outcome> conformed = new ArrayList<>(raw.outcomes().size());
    for (final EmbeddingResponse.Outcome outcome : raw.outcomes()) {
      if (!(outcome instanceof EmbeddingResponse.Outcome.Embedded embedded)) {
        conformed.add(outcome);
        continue;
      }
      try {
        final CanonicalEmbedding original = embedded.embedding();
        final float[] vector = EmbeddingNormalizer.conform(original.vector(), model, reduction);
        conformed.add(
            new EmbeddingResponse.Outcome.Embedded(
                new CanonicalEmbedding(
                    original.providerId(),
                    original.modelId(),
                    vector.length,
                    original.createdAt(),
                    original.usage(),
                    true,
                    original.metadata(),
                    vector)));
      } catch (final EmbeddingTransportException mismatch) {
        // A width mismatch is per-input, not fatal to the batch, and is never retried: the provider
        // will return the same width next time.
        conformed.add(
            new EmbeddingResponse.Outcome.Failed(mismatch.reason(), mismatch.getMessage()));
      }
    }
    return new EmbeddingResponse(conformed, raw.usage());
  }

  /**
   * Fills every outcome with the same refusal.
   *
   * @param request the request
   * @param outcomes the outcome array to fill
   * @param reason the neutral failure kind
   * @param detail an operator-facing note
   * @param started when the request began
   * @return the response
   */
  private EmbeddingResponse refuseAll(
      final EmbeddingRequest request,
      final EmbeddingResponse.Outcome[] outcomes,
      final EmbeddingFailure reason,
      final String detail,
      final long started) {
    for (int i = 0; i < outcomes.length; i++) {
      outcomes[i] = new EmbeddingResponse.Outcome.Failed(reason, detail);
    }
    metrics.failed(reason, outcomes.length);
    audit.refused(request.tenant(), request.modelId(), reason, outcomes.length);
    metrics.requestCompleted(request.size(), System.nanoTime() - started);
    return new EmbeddingResponse(List.of(outcomes), EmbeddingUsage.FREE);
  }

  /**
   * Waits between attempts.
   *
   * @param millis how long
   */
  private static void sleep(final long millis) {
    if (millis <= 0L) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
