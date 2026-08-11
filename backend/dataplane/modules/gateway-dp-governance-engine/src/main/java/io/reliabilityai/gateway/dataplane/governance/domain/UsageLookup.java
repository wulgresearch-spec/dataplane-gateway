package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import java.util.Optional;

/**
 * Reads consumption counters during one evaluation.
 *
 * <p>Passed in rather than called through a port so the decision core stays a pure function of its
 * arguments: given the same request, the same policy and the same lookup, the same verdict comes
 * out every time, which is the whole basis of replaying a governance decision during an audit (Doc
 * 21 GV-D12). The application layer memoises a real port behind this interface, so a chain that
 * mentions the same node twice reads its counter once.
 *
 * <p>Empty means unknown, and unknown is not zero. A mandatory ceiling checked against an unknown
 * counter cannot be enforced, and the evaluator refuses rather than admitting past a cap it cannot
 * see (Doc 21 GC-5/GC-8).
 */
@FunctionalInterface
public interface UsageLookup {

  /** A lookup that knows nothing — every consumption-based mandatory ceiling refuses. */
  UsageLookup UNKNOWN = scope -> Optional.empty();

  /**
   * Reads the counters at one node.
   *
   * @param scope the node whose counters are wanted
   * @return the reading, or empty when unknown
   */
  Optional<PolicyUsage> at(PolicyScopeRef scope);
}
