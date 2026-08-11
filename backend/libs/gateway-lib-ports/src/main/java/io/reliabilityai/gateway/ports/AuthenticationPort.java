package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The inbound authentication port implemented by the AuthN node (C6, Doc 37 §7, AD-002/AD-019).
 * Authenticates the principal from the forwarded transport identity and resolves tenant scope only
 * after success; NEVER authorizes (Doc 37 §ANZ). Fails closed on unknown/invalid identity.
 */
public interface AuthenticationPort {

  /**
   * Authenticates the principal and resolves tenant scope (Doc 37 §6).
   *
   * @param transportIdentity the unauthenticated forwarded transport identity (Doc 30 §IAB)
   * @param requestContext the request context
   * @return an {@link AuthenticationResult}: authenticated (with principal + tenant) or
   *     unauthenticated
   */
  AuthenticationResult authenticate(
      ForwardedTransportIdentity transportIdentity, RequestContext requestContext);

  /**
   * The terminal authentication outcome (Doc 37 §14). Fail-closed: unknown/invalid ⇒
   * unauthenticated, with no principal and no tenant scope.
   */
  sealed interface AuthenticationResult
      permits AuthenticationResult.Authenticated, AuthenticationResult.Unauthenticated {

    /**
     * A successful authentication carrying the principal and resolved tenant scope.
     *
     * @param principal the authenticated principal (Doc 37)
     * @param tenant the resolved tenant scope (post-auth, Doc 32 §SPT)
     */
    record Authenticated(PrincipalContext principal, TenantContext tenant)
        implements AuthenticationResult {
      /** Compact constructor validating principal and tenant presence. */
      public Authenticated {
        Preconditions.requireNonNull(principal, "principal");
        Preconditions.requireNonNull(tenant, "tenant");
      }
    }

    /**
     * A failed authentication (fail closed; Doc 37 §D5).
     *
     * @param reason the content-free denial reason
     */
    record Unauthenticated(String reason) implements AuthenticationResult {
      /** Compact constructor validating the reason. */
      public Unauthenticated {
        Preconditions.requireNonBlank(reason, "reason");
      }
    }
  }
}
