package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import java.util.Map;

/**
 * One immutable, internally-consistent generation of compiled policy for the whole node (Doc 21
 * §12, AD-022).
 *
 * <p>Whole-world rather than per-tenant, because the alternative is a decision evaluated against a
 * half-applied mix of two generations — organization policy from v9, project policy from v10 —
 * which is a state no operator authored and no auditor can reproduce. A snapshot is atomic or it is
 * nothing.
 *
 * <p><b>Thread model.</b> Deeply immutable once constructed. The request path holds a reference to
 * one snapshot for the duration of a decision and never synchronises on anything; a reload
 * publishes a whole new snapshot by a single reference swap. There is no lock on the read path
 * because there is nothing to lock — in-flight requests simply finish against the generation they
 * started on, which is also the property that makes their recorded policy version truthful.
 *
 * <p>Lookup is by {@link PolicyScopeRef}, so resolving a chain is a bounded number of hash probes
 * with no scanning, no prefix matching and no walking of a tree.
 */
public final class PolicySnapshot {

  private final PolicyVersion version;
  private final Map<PolicyScopeRef, CompiledPolicy> byScope;

  private PolicySnapshot(
      final PolicyVersion version, final Map<PolicyScopeRef, CompiledPolicy> byScope) {
    this.version = version;
    this.byScope = byScope;
  }

  /**
   * Creates a snapshot from already-compiled documents.
   *
   * @param version the generation
   * @param byScope the compiled documents, keyed by node
   * @return the immutable snapshot
   */
  public static PolicySnapshot of(
      final PolicyVersion version, final Map<PolicyScopeRef, CompiledPolicy> byScope) {
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(byScope, "byScope");
    return new PolicySnapshot(version, Map.copyOf(byScope));
  }

  /**
   * The empty generation a node holds before any policy has been installed.
   *
   * <p>Empty means "no statements", which under most-restrictive-wins means every request is
   * unconstrained by this snapshot. That is safe only because the engine refuses outright when no
   * snapshot has been installed at all — see {@code GovernanceEngine}, which distinguishes "policy
   * says nothing" from "there is no policy" and fails closed on the second.
   *
   * @return the empty snapshot
   */
  public static PolicySnapshot empty() {
    return new PolicySnapshot(PolicyVersion.NONE, Map.of());
  }

  /**
   * The generation this snapshot represents.
   *
   * @return the policy version
   */
  public PolicyVersion version() {
    return version;
  }

  /**
   * How many documents this generation contains.
   *
   * @return the document count
   */
  public int size() {
    return byScope.size();
  }

  /**
   * The compiled document at one node.
   *
   * @param scope the node
   * @return the compiled document, or {@code null} when the node has none
   */
  public CompiledPolicy policyAt(final PolicyScopeRef scope) {
    return byScope.get(scope);
  }

  /**
   * Folds this generation down a scope chain.
   *
   * <p>The expensive operation in the module, which is exactly why the result is cached rather than
   * recomputed: it walks the chain, probes for a document at each node, and merges what it finds.
   * Nodes with no document contribute nothing, which is the correct behaviour under a
   * most-restrictive-wins merge — silence is not permission, it is simply absence of an opinion,
   * and any actual permission still has to come from a document that granted it.
   *
   * @param chain the scope chain to fold for
   * @return the merged effective policy
   */
  public EffectivePolicy effectiveFor(final ScopeChain chain) {
    Preconditions.requireNonNull(chain, "chain");
    final EffectivePolicy.Builder builder = EffectivePolicy.builder(chain, version);
    for (final PolicyScopeRef ref : chain.refs()) {
      final CompiledPolicy compiled = byScope.get(ref);
      if (compiled != null) {
        builder.apply(compiled);
      }
    }
    return builder.build();
  }
}
