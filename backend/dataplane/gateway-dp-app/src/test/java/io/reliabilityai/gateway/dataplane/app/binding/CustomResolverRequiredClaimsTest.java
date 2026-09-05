package io.reliabilityai.gateway.dataplane.app.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A resolver an operator wrote must be able to declare the claims it reads.
 *
 * <p>The declaration used to be recovered by narrowing to the shipped implementation, so any other
 * resolver was handed an empty set: the verifier forwarded nothing, the resolver's claims never
 * arrived, and the nodes it would have built silently disappeared — the original defect, intact,
 * for exactly the deployments that had customised this seam. Owning the declaration on the
 * interface is what closes that, so these tests exercise the contract rather than the class.
 */
class CustomResolverRequiredClaimsTest {

  /** A resolver written outside this package would look like this. */
  private static final class CustomResolver implements GovernanceScopeResolver {

    @Override
    public Set<String> requiredClaims() {
      return Set.of("custom_api_key");
    }

    @Override
    public ScopeChain resolve(final PrincipalContext principal, final TenantContext tenant) {
      return ScopeChain.builder()
          .organization(tenant.tenantScope().org())
          .apiKey(principal.claims().get("custom_api_key"))
          .build();
    }
  }

  @Test
  void aCustomResolverDeclaresItsOwnClaimsThroughTheContract() {
    final GovernanceScopeResolver resolver = new CustomResolver();

    // Read through the interface type: this is exactly how GatewayRuntime asks.
    assertThat(resolver.requiredClaims()).containsExactly("custom_api_key");
  }

  @Test
  void aResolverThatDeclaresNothingGetsTheSafeDefault() {
    // A lambda is still a legal resolver — the interface stays functional — and declares nothing.
    final GovernanceScopeResolver lambda =
        (principal, tenant) -> ScopeChain.forTenant(tenant.tenantScope());

    assertThat(lambda.requiredClaims()).isEmpty();
  }

  @Test
  void theShippedResolverStillDeclaresItsConfiguredNames() {
    final GovernanceScopeResolver resolver =
        new ClaimBasedScopeResolver("akid", "ptype", java.util.Optional.empty());

    assertThat(resolver.requiredClaims()).containsExactlyInAnyOrder("akid", "ptype");
  }

  @Test
  void aCustomResolverBuildsItsApiKeyNodeWhenTheDeclaredClaimIsPresent() {
    // Proves the declaration and the consumption agree: the claim the resolver declared is the one
    // it reads, so forwarding that name is sufficient for the node to appear.
    final GovernanceScopeResolver resolver = new CustomResolver();
    final PrincipalContext principal =
        new PrincipalContext(
            new io.reliabilityai.gateway.canonical.identity.PrincipalId("principal-a"),
            java.util.Map.of("custom_api_key", "K123"),
            "jwt",
            "decision-1");

    final ScopeChain chain =
        resolver.resolve(
            principal,
            new TenantContext(
                io.reliabilityai.gateway.canonical.identity.TenantScope.of("org-a", "tenant-a")));

    assertThat(chain.refs()).contains(PolicyScopeRef.of(PolicyScope.API_KEY, "K123"));
  }
}
