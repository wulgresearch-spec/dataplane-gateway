package io.reliabilityai.gateway.canonical.context;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * The authenticated principal context (C6, Doc 33 §10.1, Doc 37). Produced by the AuthN node.
 *
 * <p>Claims are carried as read-only data; the AuthN node <em>attaches</em> claims and NEVER
 * evaluates them for access — authorization is C4's (Doc 37 §ANZ-8, AD-019). Immutable; claims are
 * defensively copied (Doc 33 §PIM).
 *
 * @param principalId the authenticated principal id (scoped, non-PII)
 * @param claims read-only principal claims
 * @param authMethod the authentication method used (e.g. token, mtls, api-key)
 * @param authDecisionId the recorded authentication decision id (replay anchor, Doc 37 §12)
 */
public record PrincipalContext(
    PrincipalId principalId, Map<String, String> claims, String authMethod, String authDecisionId) {

  /** Compact constructor validating required fields and defensively copying claims. */
  public PrincipalContext {
    Preconditions.requireNonNull(principalId, "principalId");
    Preconditions.requireNonBlank(authMethod, "authMethod");
    Preconditions.requireNonBlank(authDecisionId, "authDecisionId");
    // Inlined copy, not Preconditions.immutableMap — see that method's javadoc (EI_EXPOSE_REP).
    claims = claims == null ? Map.of() : Map.copyOf(claims);
  }
}
