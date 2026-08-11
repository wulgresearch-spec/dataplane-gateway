package io.reliabilityai.gateway.dataplane.authn.api;

import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;

/**
 * The identity-verification seam (Doc 37 §9/§TIM). Given the unauthenticated forwarded transport
 * identity and the pinned verification-key snapshot, it verifies the credential (signature, issuer,
 * audience, expiry/nbf) against the pinned <b>public</b> keys and honours the cached revocation
 * state (Doc 37 VKR-4/VKR-5) — <b>never</b> calling an online IdP/JWKS on the hot path (Doc 37
 * VKR-3/IAU-A4). The token/claim format is deferred to Doc 06 §9.5/Doc 12 (Doc 37 §L IAU-L2), so it
 * lives behind this port. It returns a typed {@link VerificationOutcome} and MUST NOT throw a raw
 * provider/IdP exception.
 */
public interface IdentityVerifierPort {

  /**
   * Verifies the forwarded identity against the pinned keys (Doc 37 §9).
   *
   * @param transportIdentity the unauthenticated forwarded transport identity (never a principal)
   * @param keySnapshot the pinned verification-key snapshot (public keys + revocation state)
   * @return a typed verification outcome (verified principal or typed rejection)
   */
  VerificationOutcome verify(
      ForwardedTransportIdentity transportIdentity, VerificationKeySnapshot keySnapshot);
}
