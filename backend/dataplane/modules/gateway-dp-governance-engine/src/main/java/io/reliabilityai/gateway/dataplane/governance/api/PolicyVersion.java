package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The identity of one immutable generation of policy (Doc 21 §12).
 *
 * <p>Carries both a human-facing {@code label} and a monotonically increasing {@code sequence}. The
 * label is what an operator reads in an audit record; the sequence is what the engine orders by,
 * and ordering is not cosmetic — a hot reload must reject a snapshot older than the one already
 * installed, or a slow publisher replaying a stale document could quietly roll a tightening back
 * (Doc 21 §12.1: a tightening's exposure window must be bounded, not reopened).
 *
 * <p>A rollback is therefore an explicit operation that installs a lower sequence deliberately,
 * never something that can happen by accident through ordinary reload.
 *
 * @param label the operator-facing version identifier
 * @param sequence the monotonic generation number
 */
public record PolicyVersion(String label, long sequence) implements Comparable<PolicyVersion> {

  /** The version of the empty bootstrap snapshot a node holds before any policy is installed. */
  public static final PolicyVersion NONE = new PolicyVersion("none", 0L);

  /** Validates the version. */
  public PolicyVersion {
    Preconditions.requireNonBlank(label, "label");
    Preconditions.requireNonNegative(sequence, "sequence");
  }

  /**
   * Creates a version.
   *
   * @param label the operator-facing identifier
   * @param sequence the monotonic generation number
   * @return the policy version
   */
  public static PolicyVersion of(final String label, final long sequence) {
    return new PolicyVersion(label, sequence);
  }

  /**
   * Whether this version supersedes another.
   *
   * @param other the version to compare against
   * @return {@code true} when this version's sequence is strictly higher
   */
  public boolean isNewerThan(final PolicyVersion other) {
    return sequence > other.sequence;
  }

  @Override
  public int compareTo(final PolicyVersion other) {
    return Long.compare(sequence, other.sequence);
  }
}
