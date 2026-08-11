package io.reliabilityai.gateway.dataplane.metering.application;

import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.dataplane.metering.domain.UnrecordedReason;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageAggregation;
import java.util.HashSet;
import java.util.Set;

/**
 * The transient per-request metering aggregate (Doc 23 §6 {@code MeteringExecution}). It
 * accumulates <b>provider usage = Σ all attempt facts</b> (Doc 23 §26), tracks the single
 * <b>delivered</b> attempt (Doc 23 §27, at most one, AT-3), and latches the first {@link
 * UnrecordedReason} so finalization fails closed on any gap/ambiguity (Doc 23 §17.1 FC-12). It is
 * <b>idempotent per {@code attemptId}</b>: a replayed/duplicate execution fact for an
 * already-recorded attempt is ignored, so provider usage is exactly-once and a re-delivered same
 * attempt never trips a false ambiguity (Doc 23 §D3/§14, UME-INV). Holds <b>no cross-request
 * state</b> (AD-021); exclusively owned by one request. Its short mutation methods are synchronized
 * (off the response critical path, Doc 23 §40; non-pinning, R-049).
 */
final class RequestMeter {

  private final Set<String> recordedAttemptIds = new HashSet<>();
  private CanonicalUsage providerTotal = UsageAggregation.ZERO;
  private int attemptCount;
  private UsageFact deliveredFact;
  private UnrecordedReason incompleteReason;

  /**
   * Records a durably-committed provider-usage fact (Doc 23 §26) and detects an ambiguous second
   * delivered attempt.
   *
   * @param fact the recorded usage fact
   */
  synchronized void recordFact(final UsageFact fact) {
    if (!recordedAttemptIds.add(fact.attemptId().value())) {
      // Idempotent per attemptId: a replayed/duplicate fact for an already-recorded attempt is
      // ignored
      // — provider usage is never double-counted and a re-delivered same attempt is not a second
      // delivered attempt (Doc 23 §D3/§14, UME-INV never-duplicate).
      return;
    }
    providerTotal = UsageAggregation.add(providerTotal, fact.units());
    attemptCount++;
    if (fact.delivered()) {
      if (deliveredFact != null) {
        // More than one DISTINCT delivered attempt is ambiguous — customer roll-up fails closed
        // (AT-3).
        latch(UnrecordedReason.AMBIGUOUS_DELIVERED);
      } else {
        deliveredFact = fact;
      }
    }
  }

  /**
   * Latches a fail-closed reason so the request cannot finalize as complete (Doc 23 FC-12).
   *
   * @param reason the unrecorded reason
   */
  synchronized void latch(final UnrecordedReason reason) {
    if (incompleteReason == null) {
      incompleteReason = reason; // keep the first cause
    }
  }

  synchronized CanonicalUsage providerTotal() {
    return providerTotal;
  }

  synchronized int attemptCount() {
    return attemptCount;
  }

  synchronized CanonicalUsage deliveredUsage() {
    return deliveredFact == null ? null : deliveredFact.units();
  }

  synchronized UnrecordedReason incompleteReason() {
    return incompleteReason;
  }
}
