package io.reliabilityai.gateway.dataplane.memory.api;

/**
 * What kind of data a memory holds, as determined by classification rather than by the caller.
 *
 * <p>Severity-ordered. Merging two classifications takes the stricter, which makes the type a
 * semilattice and lets classification compose the same way policy does.
 *
 * <p><b>{@link #UNCLASSIFIED} is not "safe".</b> It means nobody has looked. Policy treats it as
 * unenforceable and refuses (MEM-21), because the alternative is that a classifier outage silently
 * converts every write into an unclassified one.
 */
public enum DataClassification {

  /** Nobody has classified this. Not a permission — an absence, and absences fail closed. */
  UNCLASSIFIED(0),

  /** Contains nothing of concern. */
  PUBLIC(1),

  /** Ordinary tenant business data. */
  INTERNAL(2),

  /** Personally identifiable information. */
  PII(3),

  /** A special category under data-protection law: health, biometrics, and the rest. */
  SENSITIVE_PII(4),

  /** Authentication material. Never storable as memory, whatever any policy says (see §10). */
  SECRET(5);

  private final int severity;

  DataClassification(final int severity) {
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
   * Returns the stricter of two classifications.
   *
   * <p>Commutative, associative and idempotent, so a record classified by several passes ends up
   * with the same answer whatever order they ran in.
   *
   * @param other the classification to merge with
   * @return whichever is stricter
   */
  public DataClassification strictest(final DataClassification other) {
    io.reliabilityai.gateway.common.Preconditions.requireNonNull(other, "other");
    return severity >= other.severity ? this : other;
  }

  /**
   * Reports whether this classification is personal data of any kind.
   *
   * @return true for {@link #PII} and {@link #SENSITIVE_PII}
   */
  public boolean personal() {
    return this == PII || this == SENSITIVE_PII;
  }

  /**
   * Reports whether content of this classification may be stored as memory at all.
   *
   * <p>Credentials never may. This is not a policy decision a tenant can override: a memory plane
   * that can be made to hold secrets becomes a credential store with no key custody, no rotation
   * and a retrieval API — which is a strictly worse secret store than having none.
   *
   * @return false for {@link #SECRET}, true otherwise
   */
  public boolean storable() {
    return this != SECRET;
  }

  /**
   * Reports whether a classification has actually been established.
   *
   * @return false for {@link #UNCLASSIFIED}
   */
  public boolean established() {
    return this != UNCLASSIFIED;
  }
}
