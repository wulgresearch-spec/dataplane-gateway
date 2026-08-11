package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;

/**
 * The single merged policy that governs one scope chain — the fold of every document from the
 * global node down to the most specific one.
 *
 * <p>This is what the request path actually evaluates against, and it is why the hierarchy costs
 * nothing at request time. Nine documents collapse into one array of at most {@link
 * PolicyType}-many entries, computed once per distinct chain per snapshot and then read millions of
 * times. Evaluation never walks the hierarchy; by the time a request arrives, the hierarchy has
 * already happened.
 *
 * <p>Immutable and safely publishable: the array is populated during the fold, never handed out,
 * and never written after {@link Builder#build()}.
 */
public final class EffectivePolicy {

  private static final PolicyType[] TYPES = PolicyType.values();

  private final ScopeChain chain;
  private final PolicyVersion version;
  private final ResolvedRule[] byType;

  private EffectivePolicy(
      final ScopeChain chain, final PolicyVersion version, final ResolvedRule[] byType) {
    this.chain = chain;
    this.version = version;
    this.byType = byType;
  }

  /**
   * The chain this policy was folded for.
   *
   * @return the scope chain
   */
  public ScopeChain chain() {
    return chain;
  }

  /**
   * The generation this policy was folded from.
   *
   * @return the policy version
   */
  public PolicyVersion version() {
    return version;
  }

  /**
   * The merged statement governing one policy type.
   *
   * @param type the policy type
   * @return the merged statement, or {@code null} when no scope in the chain spoke about it
   */
  public ResolvedRule ruleFor(final PolicyType type) {
    return byType[type.ordinal()];
  }

  /**
   * Starts folding a chain.
   *
   * @param chain the chain being folded
   * @param version the generation being folded from
   * @return a new builder
   */
  public static Builder builder(final ScopeChain chain, final PolicyVersion version) {
    return new Builder(chain, version);
  }

  /** Folds documents into an effective policy, one scope at a time, least specific first. */
  public static final class Builder {

    private final ScopeChain chain;
    private final PolicyVersion version;
    private final ResolvedRule[] byType = new ResolvedRule[TYPES.length];

    private Builder(final ScopeChain chain, final PolicyVersion version) {
      this.chain = Preconditions.requireNonNull(chain, "chain");
      this.version = Preconditions.requireNonNull(version, "version");
    }

    /**
     * Folds one document in. The first document to speak about a type sets it; every later one
     * merges against what is already there, and the merge can only tighten.
     *
     * @param compiled the compiled document for the next node down the chain
     * @return this builder
     */
    public Builder apply(final CompiledPolicy compiled) {
      Preconditions.requireNonNull(compiled, "compiled");
      for (final PolicyType type : TYPES) {
        final var rule = compiled.ruleFor(type);
        if (rule == null) {
          continue;
        }
        final ResolvedRule incoming = new ResolvedRule(rule, compiled.scope());
        final ResolvedRule existing = byType[type.ordinal()];
        byType[type.ordinal()] =
            existing == null ? incoming : PolicyMerge.merge(existing, incoming);
      }
      return this;
    }

    /**
     * Freezes the fold.
     *
     * @return the effective policy
     */
    public EffectivePolicy build() {
      return new EffectivePolicy(chain, version, byType);
    }
  }
}
