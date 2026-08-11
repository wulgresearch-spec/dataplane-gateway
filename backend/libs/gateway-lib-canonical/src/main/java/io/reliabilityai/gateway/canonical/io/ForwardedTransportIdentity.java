package io.reliabilityai.gateway.canonical.io;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * The <em>unauthenticated</em> transport identity forwarded by the gateway to the AuthN node (Doc
 * 33 §10.2, Doc 30 §IAB-4, Doc 37 §4). This is NOT a principal — it is transport metadata (bearer
 * token / API key material / mTLS peer info). Principal authentication happens only at C6 (Doc 37);
 * the gateway forwards, it never authenticates (Doc 30 §IAB). Immutable; headers copied.
 *
 * @param scheme the credential scheme (e.g. bearer, api-key, mtls)
 * @param credentialMaterial the raw, unauthenticated credential material (validated at C6)
 * @param transportAttributes additional transport attributes (e.g. mTLS peer subject)
 */
public record ForwardedTransportIdentity(
    String scheme, String credentialMaterial, Map<String, String> transportAttributes) {

  /** Compact constructor validating the scheme and defensively copying transport attributes. */
  public ForwardedTransportIdentity {
    Preconditions.requireNonBlank(scheme, "scheme");
    Preconditions.requireNonNull(credentialMaterial, "credentialMaterial");
    // Inlined copy, not Preconditions.immutableMap — see that method's javadoc (EI_EXPOSE_REP).
    transportAttributes = transportAttributes == null ? Map.of() : Map.copyOf(transportAttributes);
  }
}
