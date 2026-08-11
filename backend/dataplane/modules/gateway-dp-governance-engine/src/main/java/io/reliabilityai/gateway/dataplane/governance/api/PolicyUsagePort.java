package io.reliabilityai.gateway.dataplane.governance.api;

import java.util.Optional;

/**
 * Reads bounded-staleness consumption counters, keyed by hierarchy node (Doc 21 §28.1 GC-2).
 *
 * <p>Keyed by {@link PolicyScopeRef} rather than by tenant because ceilings are authored at every
 * level: a per-project requests-per-minute cap has to be checked against that project's counter,
 * not its organization's, or a busy sibling project would exhaust it. Keying by node also keeps the
 * isolation property structural — a lookup can only ever return the counter for the node asked for
 * (AD-021).
 *
 * <p>Implementations serve an already-replicated local view. This is a read, never a fetch: a
 * network call here would put the coordination tier on the critical path of every admission.
 *
 * <p>An empty result is not zero. It means "unknown", and the engine treats unknown consumption
 * against a mandatory ceiling as a refusal, because admitting against an unknown counter silently
 * disables the ceiling (GC-5/GC-8).
 */
public interface PolicyUsagePort {

  /** A port that knows nothing — every mandatory ceiling refuses. Correct only in tests. */
  PolicyUsagePort UNKNOWN = scope -> Optional.empty();

  /**
   * Reads the counters for one hierarchy node.
   *
   * @param scope the node to read consumption for
   * @return the current reading, or empty when unknown
   */
  Optional<PolicyUsage> usageFor(PolicyScopeRef scope);
}
