package io.reliabilityai.gateway.dataplane.reliability.api;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.List;

/**
 * The immutable per-request invocation plan (Doc 20 §6), derived from the Router's immutable
 * decision (Doc 19 §14): the canonical request, the ordered candidate list (primary + failover —
 * the Engine executes within it and never invents candidates, RE-D3), the stable correlation id
 * (deterministic backoff seed, RE-D9), the absolute total deadline (Doc 20 §9), and whether the
 * operation is <b>idempotent or duplicate-suppressible</b> (Doc 20 §21). Immutable.
 *
 * @param request the canonical request
 * @param candidates the ordered candidate route targets (must be non-empty)
 * @param correlationId the stable correlation id (backoff seed)
 * @param deadline the absolute total deadline instant
 * @param validUntil the routing-decision TTL instant (Doc 19 §30.1 RC-5); the Engine refuses to
 *     execute a decision whose TTL has lapsed — it surfaces {@code ROUTING_DECISION_STALE} so the
 *     pipeline requests a fresh, re-hard-filtered decision (RC-6, PR-A15), never executing a stale
 *     route
 * @param idempotent whether the operation is idempotent / duplicate-suppressible (Doc 20 §21); when
 *     {@code false}, an <em>ambiguous-execution</em> failure (a {@code TIMEOUT}, which may have
 *     executed a side effect upstream) is <b>never retried</b> — it surfaces on first failure, fail
 *     closed over duplicate (§21, RE-INV)
 */
public record InvocationPlan(
    CanonicalRequest request,
    List<RouteTarget> candidates,
    CorrelationId correlationId,
    Instant deadline,
    Instant validUntil,
    boolean idempotent) {

  /** Compact constructor validating required fields and copying the candidate list. */
  public InvocationPlan {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(deadline, "deadline");
    Preconditions.requireNonNull(validUntil, "validUntil");
    candidates = candidates == null ? List.of() : List.copyOf(candidates);
    if (candidates.isEmpty()) {
      throw new IllegalArgumentException("candidates must not be empty");
    }
  }
}
