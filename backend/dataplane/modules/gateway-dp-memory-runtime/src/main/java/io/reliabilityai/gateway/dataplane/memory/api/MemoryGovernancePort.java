package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;

/**
 * The seam to the Governance Engine (AD-026 §5, MEM-7).
 *
 * <p><b>The runtime never authorizes. It asks, and it obeys.</b> This port is deliberately narrow:
 * it offers no way to read a policy, evaluate a rule, or interpret a verdict beyond allow and deny.
 * Giving the memory plane richer access to governance would create a second policy interpreter that
 * would eventually disagree with the first, and two authorization models that disagree is worse
 * than either alone.
 *
 * <p>Declared here rather than imported from the governance module on purpose. This milestone must
 * not modify Governance, and the memory runtime must not depend on it (MEM-5); the composition root
 * adapts this port to the real engine, exactly as the Agent Runtime's session port is adapted to
 * the pipeline.
 */
public interface MemoryGovernancePort {

  /** What governance decided. */
  enum Verdict {
    /** Proceed. */
    ALLOW,

    /** Refuse, and audit the refusal. */
    DENY,

    /**
     * Governance could not reach a decision.
     *
     * <p>Not a quiet allow (MEM-21). An engine that is down, a snapshot that is missing, or a scope
     * that resolves to nothing all land here, and all of them refuse — because the alternative
     * converts a broken dependency into an open door.
     */
    UNENFORCEABLE
  }

  /**
   * A governance answer.
   *
   * @param verdict what was decided
   * @param reasonCode a stable, low-cardinality code suitable as a metrics dimension
   */
  record Decision(Verdict verdict, String reasonCode) {

    /** A permitted decision. */
    public static final Decision ALLOWED = new Decision(Verdict.ALLOW, "permitted");

    /**
     * Validates the decision.
     *
     * @param verdict the verdict
     * @param reasonCode the stable code
     */
    public Decision {
      Preconditions.requireNonNull(verdict, "verdict");
      Preconditions.requireNonBlank(reasonCode, "reasonCode");
    }

    /**
     * Reports whether the operation may proceed.
     *
     * @return true only for {@link Verdict#ALLOW}
     */
    public boolean admits() {
      return verdict == Verdict.ALLOW;
    }
  }

  /**
   * Asks whether a principal may write this memory.
   *
   * @param principal who is writing
   * @param scope where, already narrowed to the principal's authority
   * @param type which memory kind
   * @param classification what the classifier established
   * @param sizeBytes how large the content is
   * @return the decision
   */
  Decision admitWrite(
      PrincipalId principal,
      MemoryScope scope,
      MemoryType type,
      DataClassification classification,
      int sizeBytes);

  /**
   * Asks whether a principal may read these memories.
   *
   * @param principal who is reading
   * @param scope where, already narrowed
   * @param types which memory kinds
   * @param mode how they intend to search
   * @return the decision
   */
  Decision admitRead(
      PrincipalId principal, MemoryScope scope, Set<MemoryType> types, RetrievalMode mode);

  /**
   * Asks whether a principal may delete this memory.
   *
   * <p>Separate from the write decision because deletion is a different power. A principal that may
   * add memories is not thereby entitled to destroy them, and conflating the two would make erasure
   * a side effect of write access.
   *
   * @param principal who is deleting
   * @param scope where, already narrowed
   * @param type which memory kind
   * @return the decision
   */
  Decision admitDelete(PrincipalId principal, MemoryScope scope, MemoryType type);
}
