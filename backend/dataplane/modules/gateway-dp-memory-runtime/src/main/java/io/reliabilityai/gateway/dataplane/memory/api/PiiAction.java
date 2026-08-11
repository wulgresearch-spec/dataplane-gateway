package io.reliabilityai.gateway.dataplane.memory.api;

/**
 * What to do with a memory that contains personal data.
 *
 * <p>Severity-ordered so that merging along a scope chain takes the strictest — a workspace may
 * tighten its tenant's rule, never relax it.
 */
public enum PiiAction {

  /** Store as-is. Only ever reachable when policy explicitly says so for non-personal content. */
  ALLOW(0),

  /** Store, but seal at rest regardless of what the encryption policy says. */
  ENCRYPT(1),

  /** Store with the personal spans removed. What is removed does not come back. */
  REDACT(2),

  /** Do not store. The write is refused and audited. */
  REFUSE(3);

  private final int severity;

  PiiAction(final int severity) {
    this.severity = severity;
  }

  /**
   * Returns the severity rank, ascending.
   *
   * @return the rank, where a higher number is stricter
   */
  public int severity() {
    return severity;
  }

  /**
   * Returns the stricter of two actions.
   *
   * @param other the action to merge with
   * @return whichever is stricter
   */
  public PiiAction strictest(final PiiAction other) {
    io.reliabilityai.gateway.common.Preconditions.requireNonNull(other, "other");
    return severity >= other.severity ? this : other;
  }

  /**
   * Reports whether this action stops the write.
   *
   * @return true only for {@link #REFUSE}
   */
  public boolean refuses() {
    return this == REFUSE;
  }

  /**
   * Reports whether this action forces sealing irrespective of the encryption policy.
   *
   * @return true for {@link #ENCRYPT}
   */
  public boolean forcesEncryption() {
    return this == ENCRYPT;
  }
}
