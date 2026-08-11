package io.reliabilityai.gateway.dataplane.eventpublisher.api;

/**
 * The between-attempt backoff seam for broker retries (Doc 07 §14 — tiered exponential backoff). It
 * bounds the retry <b>rate</b> (not just the count) so a transient broker fault is not hammered
 * with back-to-back sends. Injected so timing stays out of the deterministic correctness core and
 * can be controlled/no-op'd in tests. A concrete implementation applies tiered/jittered delays.
 */
@FunctionalInterface
public interface RetryBackoff {

  /**
   * A no-op backoff (immediate retries) — for tests / where the caller schedules pacing elsewhere.
   */
  RetryBackoff NONE = attemptNumber -> {};

  /**
   * Awaits the backoff delay before the given (1-based) retry attempt (Doc 07 §14).
   *
   * @param attemptNumber the attempt about to be made ({@code >= 2}, since the first is not
   *     delayed)
   * @throws InterruptedException if interrupted while backing off (cancellation)
   */
  void await(int attemptNumber) throws InterruptedException;
}
