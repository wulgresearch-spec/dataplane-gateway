package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import java.util.Optional;

/**
 * Resolves the governance hierarchy address from the tenant scope plus operator-named identity
 * claims.
 *
 * <p>The chain is assembled in specificity order:
 *
 * <pre>
 *   GLOBAL          always
 *   ORGANIZATION    tenantScope.org()          — authenticated, never a claim
 *   WORKSPACE       tenantScope.workspace()    — authenticated, absent when the scope has none
 *   PROJECT         tenantScope.project()      — authenticated, absent when the scope has none
 *   ENVIRONMENT     this node's configured environment — the node knows where it runs
 *   API_KEY         principal claim, under the operator-named key
 *   USER / SERVICE_ACCOUNT   the principal id, classified by an operator-named claim
 * </pre>
 *
 * <p><b>An absent claim omits its node; it never invents one.</b> That is the whole discipline
 * here. Omitting a node means no policy is attached at that level, which under a
 * most-restrictive-wins merge contributes nothing — safe. Inventing one, by defaulting an unnamed
 * key to some placeholder id, would attach every unkeyed request in the fleet to a single shared
 * node and let one tenant's key policy govern another's traffic.
 *
 * <p><b>Human or machine is asked, not guessed.</b> Whether a principal is a user or a service
 * account decides which node its policy hangs off. Inferring it from the authentication method
 * would be wrong the first time an operator issues a JWT to a batch job. When the classifying claim
 * is absent the principal node is omitted entirely, so the request is governed by everything above
 * it and by no principal-level policy — the honest answer to "I do not know what kind of principal
 * this is".
 */
public final class ClaimBasedScopeResolver implements GovernanceScopeResolver {

  /** The claim value that marks a principal as non-human. */
  public static final String SERVICE_ACCOUNT_MARKER = "service_account";

  private final String apiKeyClaim;
  private final String principalTypeClaim;
  private final Optional<String> environmentId;

  /**
   * Creates the resolver.
   *
   * @param apiKeyClaim the claim carrying the API key id, or blank when keys are not scoped
   * @param principalTypeClaim the claim classifying the principal as human or service account
   * @param environmentId this node's deployment environment, or empty when it is not
   *     environment-scoped
   */
  public ClaimBasedScopeResolver(
      final String apiKeyClaim,
      final String principalTypeClaim,
      final Optional<String> environmentId) {
    this.apiKeyClaim = apiKeyClaim == null ? "" : apiKeyClaim;
    this.principalTypeClaim = principalTypeClaim == null ? "" : principalTypeClaim;
    this.environmentId = Preconditions.requireNonNull(environmentId, "environmentId");
  }

  /**
   * Creates a resolver that scopes only to the authenticated tenant hierarchy — no environment, key
   * or principal nodes. The correct starting point for a deployment that has not yet decided what
   * its claims are called.
   *
   * @return a tenant-only resolver
   */
  public static ClaimBasedScopeResolver tenantOnly() {
    return new ClaimBasedScopeResolver("", "", Optional.empty());
  }

  @Override
  public ScopeChain resolve(final PrincipalContext principal, final TenantContext tenant) {
    Preconditions.requireNonNull(principal, "principal");
    Preconditions.requireNonNull(tenant, "tenant");

    final ScopeChain.Builder chain =
        ScopeChain.builder()
            .organization(tenant.tenantScope().org())
            .workspace(tenant.tenantScope().workspace())
            .project(tenant.tenantScope().project())
            .environment(environmentId.orElse(null))
            .apiKey(claim(principal, apiKeyClaim));

    final String principalType = claim(principal, principalTypeClaim);
    if (principalType == null) {
      return chain.build();
    }
    final String principalId = principal.principalId().value();
    return SERVICE_ACCOUNT_MARKER.equals(principalType)
        ? chain.serviceAccount(principalId).build()
        : chain.user(principalId).build();
  }

  /** A claim's value, or null when the claim was not named or not asserted. */
  private static String claim(final PrincipalContext principal, final String name) {
    if (name.isBlank()) {
      return null;
    }
    final String value = principal.claims().get(name);
    return value == null || value.isBlank() ? null : value;
  }
}
