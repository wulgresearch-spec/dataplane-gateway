package io.reliabilityai.gateway.dataplane.memory.pii;

/**
 * Observability for the detection engine (AD-029 §9).
 *
 * <p>Every parameter is a type, a rule identifier, a size or a duration. **None of them is scanned
 * content**, and none can be widened to carry it without changing this interface. A metrics port
 * that could report "what was found" would export the very data the engine exists to protect, to
 * the least protected destination a service has.
 *
 * <p>The suppression and rejection counters matter as much as the detection counter. A deployment
 * whose {@code droppedBelowConfidence} rate is climbing is a deployment whose recall is quietly
 * falling, and nothing else in the system would show that.
 */
public interface PiiMetricsPort {

  /** A port that discards everything. */
  PiiMetricsPort NOOP =
      new PiiMetricsPort() {
        @Override
        public void scanned(final int characters, final long nanos) {}

        @Override
        public void detected(final PiiType type) {}

        @Override
        public void validatorRejected(final PiiType type) {}

        @Override
        public void droppedBelowConfidence(final PiiType type) {}

        @Override
        public void suppressedByAllowRule(final String ruleId) {}

        @Override
        public void budgetExhausted(final String ruleId) {}

        @Override
        public void truncated() {}
      };

  /**
   * A body was scanned.
   *
   * @param characters how long it was
   * @param nanos how long the scan took
   */
  void scanned(int characters, long nanos);

  /**
   * A span was reported.
   *
   * @param type what was found
   */
  void detected(PiiType type);

  /**
   * A match failed its checksum or range check.
   *
   * <p>A healthy deployment sees a great many of these — they are the false positives the
   * validators exist to remove, counted rather than hidden.
   *
   * @param type what the match would have been
   */
  void validatorRejected(PiiType type);

  /**
   * A match scored below the confidence floor and was not reported.
   *
   * @param type what the match would have been
   */
  void droppedBelowConfidence(PiiType type);

  /**
   * A detection was suppressed by an exemption rule.
   *
   * @param ruleId the exemption that suppressed it
   */
  void suppressedByAllowRule(String ruleId);

  /**
   * A rule exceeded its step budget and was abandoned.
   *
   * <p>Non-zero means a pattern is pathological against real traffic, and that the scan it happened
   * in was incomplete. This deserves an alert, not a tile.
   *
   * @param ruleId the abandoned rule
   */
  void budgetExhausted(String ruleId);

  /** A body was longer than the engine scans, so part of it was never examined. */
  void truncated();
}
