package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * A bounded-staleness reading of consumption at one hierarchy node (Doc 21 §28/§28.1).
 *
 * <p>The engine owns none of these numbers — C5 owns the authoritative ledger and the engine
 * consumes a replicated view of it (GC-1). {@code asOf} is therefore the most important field: it
 * is what lets the engine tell the difference between "this tenant has used 40% of its budget" and
 * "this tenant had used 40% of its budget at some unknown point in the past". Enforcing a spend
 * ceiling against a reading of unknown age is indistinguishable from not enforcing it, so a reading
 * older than the configured tolerance is refused rather than trusted (GC-6).
 *
 * @param requestsInWindow requests consumed in the current quota window
 * @param requestsThisMinute requests consumed in the current minute
 * @param tokensThisMinute tokens consumed in the current minute
 * @param concurrentRequests requests currently in flight
 * @param spentTodayMicros normalized spend consumed today
 * @param spentThisMonthMicros normalized spend consumed this month
 * @param asOf the instant this reading was taken
 */
public record PolicyUsage(
    long requestsInWindow,
    long requestsThisMinute,
    long tokensThisMinute,
    long concurrentRequests,
    long spentTodayMicros,
    long spentThisMonthMicros,
    Instant asOf) {

  /** Validates the reading. */
  public PolicyUsage {
    Preconditions.requireNonNegative(requestsInWindow, "requestsInWindow");
    Preconditions.requireNonNegative(requestsThisMinute, "requestsThisMinute");
    Preconditions.requireNonNegative(tokensThisMinute, "tokensThisMinute");
    Preconditions.requireNonNegative(concurrentRequests, "concurrentRequests");
    Preconditions.requireNonNegative(spentTodayMicros, "spentTodayMicros");
    Preconditions.requireNonNegative(spentThisMonthMicros, "spentThisMonthMicros");
    Preconditions.requireNonNull(asOf, "asOf");
  }

  /**
   * A reading with everything at zero — a node that has consumed nothing yet.
   *
   * @param asOf the instant the reading was taken
   * @return the empty reading
   */
  public static PolicyUsage none(final Instant asOf) {
    return new PolicyUsage(0L, 0L, 0L, 0L, 0L, 0L, asOf);
  }

  /**
   * Whether this reading is recent enough to enforce a hard cap against.
   *
   * @param now the injected-clock instant
   * @param tolerance the configured freshness window
   * @return {@code true} when the reading is within tolerance
   */
  public boolean isFreshAt(final Instant now, final java.time.Duration tolerance) {
    return !asOf.plus(tolerance).isBefore(now);
  }

  /**
   * The consumed amount relevant to a ceiling type.
   *
   * @param type the quota or budget type
   * @return the counter this type is enforced against, or zero when the type is not usage-based
   */
  public long consumedFor(final PolicyType type) {
    return switch (type) {
      case MAX_REQUESTS -> requestsInWindow;
      case MAX_RPM -> requestsThisMinute;
      case MAX_TPM -> tokensThisMinute;
      case CONCURRENCY_LIMIT -> concurrentRequests;
      case DAILY_BUDGET -> spentTodayMicros;
      case MONTHLY_BUDGET -> spentThisMonthMicros;
      default -> 0L;
    };
  }
}
