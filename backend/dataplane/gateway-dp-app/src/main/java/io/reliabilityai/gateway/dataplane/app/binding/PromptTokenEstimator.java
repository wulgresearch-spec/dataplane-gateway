package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import java.util.OptionalLong;

/**
 * Counts, or bounds, the prompt size of a request before it is sent anywhere.
 *
 * <p>Governance needs a prompt size to enforce context ceilings and token-rate caps, and the Cost
 * Engine needs one to project spend. Nothing upstream in this gateway produces it: usage arrives
 * from the provider, which is long after admission. This seam is where that number comes from.
 *
 * <p><b>The contract is an upper bound, not an estimate.</b> An implementation must never return
 * fewer tokens than the request actually consumes, because every consumer of this number compares
 * it against a ceiling. Under-count and a 100k-token prompt slips past a 4k cap; over-count and a
 * legitimate request is refused. Only one of those is a security failure, so the contract is
 * deliberately asymmetric: bound high, or answer {@link OptionalLong#empty()} and let the caller
 * refuse.
 *
 * <p>Returning empty is a first-class answer, not a failure to be papered over. A caller must not
 * substitute zero for it — zero passes every ceiling, which is indistinguishable from having no
 * ceiling.
 */
@FunctionalInterface
public interface PromptTokenEstimator {

  /** An estimator that never answers, so every token and budget policy refuses. */
  PromptTokenEstimator UNKNOWN = request -> OptionalLong.empty();

  /**
   * Bounds the prompt size of a request.
   *
   * @param request the canonical request
   * @return an upper bound on prompt tokens, or empty when no bound can be given
   */
  OptionalLong promptTokens(CanonicalRequest request);
}
