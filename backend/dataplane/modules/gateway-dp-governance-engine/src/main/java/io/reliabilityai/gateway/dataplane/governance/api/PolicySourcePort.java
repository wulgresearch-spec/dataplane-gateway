package io.reliabilityai.gateway.dataplane.governance.api;

import java.util.List;
import java.util.Optional;

/**
 * Where authored policy documents come from (Doc 21 §7 {@code PolicySnapshotPort}, §39).
 *
 * <p>The engine is the enforcement point, not the authority: the C4 control plane authors, versions
 * and distributes policy, and this port is the only way any of it enters the module (GV-A12).
 * Nothing downstream of this interface can create a rule, edit one, or invent a default — the
 * compiler that consumes this output is a translator, not an author.
 *
 * <p>Called at load time, never on the request path. An implementation reading a file or a
 * distributed cache is fine; the request path only ever touches the compiled snapshot that
 * resulted.
 *
 * <p>Returning empty means the source has nothing to offer right now — a distribution outage, say.
 * That is deliberately distinct from returning an empty document list, which means "the authority
 * says there is no policy". The first leaves the running snapshot in place; the second would
 * install an empty one.
 */
public interface PolicySourcePort {

  /** A source that never publishes anything. */
  PolicySourcePort EMPTY = Optional::empty;

  /**
   * Offers the current generation of authored policy.
   *
   * @return the version and its documents, or empty when the source cannot answer
   */
  Optional<PolicyBundle> load();

  /**
   * One complete, internally-consistent generation of authored policy.
   *
   * <p>Whole-generation rather than per-document because a decision must evaluate against exactly
   * one version and never a half-applied mix of two (Doc 21 §12). Distributing documents
   * individually would make partial application the normal case rather than an impossible one.
   *
   * @param version the generation these documents belong to
   * @param policies the authored documents
   */
  record PolicyBundle(PolicyVersion version, List<GovernancePolicy> policies) {

    /** Validates the bundle. */
    public PolicyBundle {
      io.reliabilityai.gateway.common.Preconditions.requireNonNull(version, "version");
      policies = policies == null ? List.of() : List.copyOf(policies);
    }
  }
}
