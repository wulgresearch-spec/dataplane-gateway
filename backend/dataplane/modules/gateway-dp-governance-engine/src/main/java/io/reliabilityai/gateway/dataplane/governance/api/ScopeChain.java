package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The ordered list of hierarchy nodes a single request is governed by, least specific first.
 *
 * <p>This is the request's address in the policy hierarchy, and it is also the key the merged
 * effective policy is cached under. Two requests with the same chain are governed identically, so
 * the expensive part — folding up to nine documents into one effective policy — happens once per
 * distinct chain per snapshot rather than once per request. In a gateway where a handful of
 * projects issue millions of calls, that is the difference between a merge on the critical path and
 * an array read.
 *
 * <p>Not a record, deliberately: the hash is precomputed once at construction because this object
 * is hashed on every single request and a record would recompute it from a nine-element list each
 * time.
 *
 * <p>The chain always begins at {@link PolicyScopeRef#global()} and is strictly increasing in
 * specificity. Strictness is enforced rather than assumed — a chain containing two nodes at the
 * same scope would make the fold's result depend on their order, and an order-dependent governance
 * decision is an ambiguous one.
 */
public final class ScopeChain {

  private final List<PolicyScopeRef> refs;
  private final int hash;

  private ScopeChain(final List<PolicyScopeRef> refs) {
    // The copy belongs here rather than at the one call site that happened to perform it. A
    // private constructor trusting its caller is only correct until a second caller appears, and
    // the guarantee is not checkable from this class as written.
    this.refs = List.copyOf(refs);
    this.hash = this.refs.hashCode();
  }

  /**
   * The nodes, least specific first.
   *
   * @return the immutable ordered chain
   */
  public List<PolicyScopeRef> refs() {
    return refs;
  }

  /**
   * Starts a chain at the global node.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builds the chain implied by a tenant scope: global, organization, and whichever of workspace
   * and project the scope populates.
   *
   * @param tenantScope the resolved tenant hierarchy
   * @return the corresponding chain
   */
  public static ScopeChain forTenant(final TenantScope tenantScope) {
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    return builder()
        .organization(tenantScope.org())
        .workspace(tenantScope.workspace())
        .project(tenantScope.project())
        .build();
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof ScopeChain chain && hash == chain.hash && refs.equals(chain.refs);
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public String toString() {
    return "ScopeChain" + refs;
  }

  /**
   * Assembles a chain. Every level except global is optional; a {@code null} or blank id means the
   * request simply is not scoped at that level, which contributes nothing to the merge.
   */
  public static final class Builder {

    private final List<PolicyScopeRef> refs = new ArrayList<>(4);

    private Builder() {
      refs.add(PolicyScopeRef.global());
    }

    /**
     * Sets the organization node.
     *
     * @param id the organization id, or null to omit
     * @return this builder
     */
    public Builder organization(final String id) {
      return add(PolicyScope.ORGANIZATION, id);
    }

    /**
     * Sets the workspace node.
     *
     * @param id the workspace id, or null to omit
     * @return this builder
     */
    public Builder workspace(final String id) {
      return add(PolicyScope.WORKSPACE, id);
    }

    /**
     * Sets the project node.
     *
     * @param id the project id, or null to omit
     * @return this builder
     */
    public Builder project(final String id) {
      return add(PolicyScope.PROJECT, id);
    }

    /**
     * Sets the environment node.
     *
     * @param id the environment id, or null to omit
     * @return this builder
     */
    public Builder environment(final String id) {
      return add(PolicyScope.ENVIRONMENT, id);
    }

    /**
     * Sets the API key node.
     *
     * @param id the API key id, or null to omit
     * @return this builder
     */
    public Builder apiKey(final String id) {
      return add(PolicyScope.API_KEY, id);
    }

    /**
     * Sets the user node.
     *
     * @param id the user id, or null to omit
     * @return this builder
     */
    public Builder user(final String id) {
      return add(PolicyScope.USER, id);
    }

    /**
     * Sets the service account node.
     *
     * @param id the service account id, or null to omit
     * @return this builder
     */
    public Builder serviceAccount(final String id) {
      return add(PolicyScope.SERVICE_ACCOUNT, id);
    }

    /**
     * Sets the per-request node, which carries constraints the caller imposed on itself.
     *
     * @param id the request id, or null to omit
     * @return this builder
     */
    public Builder request(final String id) {
      return add(PolicyScope.REQUEST, id);
    }

    private Builder add(final PolicyScope scope, final String id) {
      if (id == null || id.isBlank()) {
        return this;
      }
      final PolicyScope last = refs.get(refs.size() - 1).scope();
      if (!scope.isMoreSpecificThan(last)) {
        throw new IllegalArgumentException(
            scope + " does not follow " + last + " in the hierarchy");
      }
      refs.add(PolicyScopeRef.of(scope, id));
      return this;
    }

    /**
     * Freezes the chain.
     *
     * @return the immutable chain
     */
    public ScopeChain build() {
      return new ScopeChain(Objects.requireNonNull(refs));
    }
  }
}
