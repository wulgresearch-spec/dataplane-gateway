package io.reliabilityai.gateway.dataplane.memory.pii;

/**
 * How much harm disclosure of a detection would do (AD-029 §4).
 *
 * <p>Ordered, and the order is load-bearing: a document's severity is the maximum over its spans,
 * so a single credit card in a page of prose classifies the whole page as {@link #CRITICAL}. That
 * is deliberate. Averaging would let an attacker dilute sensitive content by padding it, and the
 * unit being protected is the record, not the span.
 */
public enum PiiSeverity {

  /** Nothing sensitive was found. */
  NONE(0),

  /** Weakly identifying on its own — an IP address, an internal identifier. */
  LOW(1),

  /** Directly identifying but widely shared — an email address, a phone number. */
  MEDIUM(2),

  /** Identifying and hard to change, or revealing of a protected characteristic. */
  HIGH(3),

  /** Immediately exploitable, or regulated to the point that storage is a decision in itself. */
  CRITICAL(4);

  private final int rank;

  /**
   * Creates a level.
   *
   * @param rank the comparable rank, higher being worse
   */
  PiiSeverity(final int rank) {
    this.rank = rank;
  }

  /**
   * The comparable rank.
   *
   * @return the rank, higher being worse
   */
  public int rank() {
    return rank;
  }

  /**
   * Returns whichever of two levels is worse.
   *
   * @param other the level to compare against
   * @return the higher-ranked level
   */
  public PiiSeverity max(final PiiSeverity other) {
    return other.rank > rank ? other : this;
  }
}
