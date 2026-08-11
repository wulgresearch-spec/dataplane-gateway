package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.api.CostEnginePort;
import io.reliabilityai.gateway.dataplane.cost.api.CostProjection;
import io.reliabilityai.gateway.dataplane.cost.api.CostRequest;
import io.reliabilityai.gateway.dataplane.cost.api.ProjectionOutcome;
import io.reliabilityai.gateway.dataplane.cost.domain.Phase;
import java.util.OptionalLong;

/**
 * Asks the Cost Engine what a request could cost, so governance can check it against a budget.
 *
 * <p>This wires up the half of the Cost Engine that existed and was never called. {@code project()}
 * is documented as producing a never-underestimated upper bound specifically so that "Governance's
 * budget check consumes it so it can never admit an over-budget request due to under-projection"
 * (Doc 22 §CE-D5/§23). Until now nothing consumed it, so budget policy had no number to work from.
 *
 * <p><b>Governance still computes no cost</b> (Doc 21 GV-D6). Every rate, FX conversion and
 * contract decision stays inside the Cost Engine; this class maps identities across the seam and
 * unwraps the answer. It performs no arithmetic on money beyond reading a field.
 *
 * <p><b>Three ways to get no answer, all of which refuse.</b> The prompt could not be bounded, the
 * caller declared no output ceiling, or the engine returned {@code CostUnavailable} because
 * pricing, FX or the contract could not be resolved. Each yields empty, which makes budget policy
 * unenforceable and therefore refusing. That is the correct direction: a request whose cost nobody
 * can compute is exactly the request a spend ceiling exists to stop.
 *
 * <p><b>Currency is checked, not assumed.</b> A budget authored in canonical micros compared
 * against a projection denominated in something else is a silent off-by-an-exchange-rate error, and
 * it would fail open whenever the projection currency was the weaker one. A mismatch is treated as
 * no answer.
 */
public final class AdmissionCostProjector {

  private final CostEnginePort costEngine;
  private final PromptTokenEstimator tokens;
  private final String canonicalCurrency;

  /**
   * Creates the projector.
   *
   * @param costEngine the Cost Engine that owns all pricing
   * @param tokens the prompt-size bound used as the projection's input
   * @param canonicalCurrency the currency governance budgets are denominated in
   */
  public AdmissionCostProjector(
      final CostEnginePort costEngine,
      final PromptTokenEstimator tokens,
      final String canonicalCurrency) {
    this.costEngine = Preconditions.requireNonNull(costEngine, "costEngine");
    this.tokens = Preconditions.requireNonNull(tokens, "tokens");
    this.canonicalCurrency = Preconditions.requireNonBlank(canonicalCurrency, "canonicalCurrency");
  }

  /**
   * Projects the spend a request could incur.
   *
   * @param requestContext correlation, idempotency and region
   * @param tenant the resolved tenant scope
   * @param model the canonical model addressed
   * @param promptTokens the bounded prompt size, or empty when it could not be bounded
   * @param declaredMaxOutputTokens the caller's declared completion ceiling
   * @return the upper bound in canonical micros, or empty when it cannot be projected
   */
  public OptionalLong project(
      final RequestContext requestContext,
      final TenantContext tenant,
      final CanonicalModelId model,
      final OptionalLong promptTokens,
      final OptionalLong declaredMaxOutputTokens) {
    if (promptTokens.isEmpty()) {
      return OptionalLong.empty();
    }
    final ProjectionOutcome outcome;
    try {
      outcome =
          costEngine.project(
              request(requestContext, tenant, model, promptTokens, declaredMaxOutputTokens));
    } catch (final RuntimeException unavailable) {
      // A pricing subsystem that throws has told us nothing about what this request costs.
      return OptionalLong.empty();
    }
    if (!(outcome instanceof CostProjection projection)) {
      return OptionalLong.empty();
    }
    if (!canonicalCurrency.equals(projection.upperBound().currency())) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(projection.upperBound().amountMicros());
  }

  /**
   * Bounds a request's prompt size.
   *
   * @param request the canonical request
   * @return the bound, or empty when none can be given
   */
  public OptionalLong promptTokens(
      final io.reliabilityai.gateway.canonical.io.CanonicalRequest request) {
    try {
      final OptionalLong bound = tokens.promptTokens(request);
      return bound == null ? OptionalLong.empty() : bound;
    } catch (final RuntimeException unavailable) {
      return OptionalLong.empty();
    }
  }

  private CostRequest request(
      final RequestContext requestContext,
      final TenantContext tenant,
      final CanonicalModelId model,
      final OptionalLong promptTokens,
      final OptionalLong declaredMaxOutputTokens) {
    return new CostRequest(
        tenant.tenantScope(),
        model,
        requestContext.region(),
        requestContext.correlationId(),
        requestContext.idempotencyKey(),
        // No attempt exists yet — routing has not happened. A deterministic admission id keeps the
        // projection replayable without inventing a random one (Doc 11 R-063).
        new AttemptId(requestContext.correlationId().value() + ":admission"),
        Phase.PROJECTION,
        promptTokens.getAsLong(),
        declaredMaxOutputTokens,
        false,
        false);
  }
}
