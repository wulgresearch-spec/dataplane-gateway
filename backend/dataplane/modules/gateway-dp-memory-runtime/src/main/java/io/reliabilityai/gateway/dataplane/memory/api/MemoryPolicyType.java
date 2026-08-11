package io.reliabilityai.gateway.dataplane.memory.api;

/**
 * The ten memory policies (AD-026 §8.1).
 *
 * <p>Each carries its own <b>merge rule</b>, which is what makes resolution along a scope chain a
 * semilattice meet: idempotent, commutative, associative and monotonically non-loosening (MEM-19).
 * Attaching the rule to the type rather than to the merging code means a new policy cannot be added
 * without someone deciding how it composes.
 */
public enum MemoryPolicyType {

  /**
   * How long a memory <em>may</em> live. Merges by minimum: any scope may shorten, none may
   * lengthen.
   */
  TTL(MergeRule.MIN),

  /**
   * How long a memory <em>must</em> live.
   *
   * <p>Merges by maximum, which looks like loosening and is not: retention is a floor, so raising
   * it tightens the obligation to keep. Where a retention floor exceeds a TTL ceiling the record is
   * retained and flagged, never silently deleted (AD-026 §8.2).
   */
  RETENTION(MergeRule.MAX),

  /** What to do when content is personal data. Merges to the strictest action. */
  PII(MergeRule.STRICTEST),

  /**
   * Which regions may hold the bytes. Merges by intersection; an empty intersection refuses the
   * write.
   */
  RESIDENCY(MergeRule.INTERSECT),

  /** Whether at-rest sealing is required. Merges so that "required" wins over "optional". */
  ENCRYPTION(MergeRule.STRICTEST),

  /** Whether deletion is suspended. Any hold anywhere in the chain wins (MEM-20). */
  LEGAL_HOLD(MergeRule.ANY),

  /** Who may delete and by what route. Merges to the strictest. */
  DELETE(MergeRule.STRICTEST),

  /** At what age a memory moves to cold storage. Merges by minimum age. */
  ARCHIVE(MergeRule.MIN),

  /**
   * Whether a write supersedes in place or appends a version. An explicit setting beats a default.
   */
  VERSIONING(MergeRule.EXPLICIT_WINS),

  /** How often a point-in-time capture is taken. Merges by minimum interval. */
  SNAPSHOT(MergeRule.MIN);

  /** How two values of a policy combine when scopes are merged. */
  public enum MergeRule {
    /** The smaller value wins. */
    MIN,
    /** The larger value wins. */
    MAX,
    /** The stricter of two enumerated actions wins. */
    STRICTEST,
    /** Only values permitted by both survive. */
    INTERSECT,
    /** True anywhere means true everywhere. */
    ANY,
    /** A value stated explicitly beats one left at its default. */
    EXPLICIT_WINS
  }

  private final MergeRule mergeRule;

  MemoryPolicyType(final MergeRule mergeRule) {
    this.mergeRule = mergeRule;
  }

  /**
   * Returns how two values of this policy combine.
   *
   * @return the merge rule
   */
  public MergeRule mergeRule() {
    return mergeRule;
  }

  /**
   * Reports whether this policy can, on its own, refuse a write.
   *
   * <p>Residency with no permitted region, PII set to refuse, and a delete policy forbidding
   * supersession all stop a write outright. The rest shape an admitted write instead.
   *
   * @return true when the policy is capable of refusing
   */
  public boolean canRefuseWrite() {
    return this == RESIDENCY || this == PII || this == DELETE;
  }
}
