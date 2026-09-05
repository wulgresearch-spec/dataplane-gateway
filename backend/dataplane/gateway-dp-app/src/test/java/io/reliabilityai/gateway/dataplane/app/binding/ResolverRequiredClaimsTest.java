package io.reliabilityai.gateway.dataplane.app.binding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The resolver declares the claims it needs, and the identity verifier is wired from that one
 * declaration.
 *
 * <p>This accessor is what keeps the two halves of the fix from drifting. The verifier must forward
 * exactly the claims the resolver reads; if the names were configured twice they could diverge, and
 * a diverged name fails silently — the claim never arrives, the governance node is never built, and
 * the policy attached to it stops participating in the merge without any signal.
 */
class ResolverRequiredClaimsTest {

  @Test
  void aTenantOnlyResolverDeclaresNoClaims() {
    assertThat(ClaimBasedScopeResolver.tenantOnly().requiredClaims()).isEmpty();
  }

  @Test
  void aConfiguredResolverDeclaresBothClaimNames() {
    final ClaimBasedScopeResolver resolver =
        new ClaimBasedScopeResolver("akid", "ptype", Optional.empty());

    assertThat(resolver.requiredClaims()).containsExactlyInAnyOrder("akid", "ptype");
  }

  @Test
  void onlyTheConfiguredHalfIsDeclared() {
    assertThat(new ClaimBasedScopeResolver("akid", "", Optional.empty()).requiredClaims())
        .containsExactly("akid");
    assertThat(new ClaimBasedScopeResolver("", "ptype", Optional.empty()).requiredClaims())
        .containsExactly("ptype");
  }

  @Test
  void aBlankOrNullClaimNameIsNotDeclaredAsARequirement() {
    // A blank name means "not scoped at that level"; requiring it would deny every request.
    assertThat(new ClaimBasedScopeResolver(null, "   ", Optional.empty()).requiredClaims())
        .isEmpty();
  }
}
