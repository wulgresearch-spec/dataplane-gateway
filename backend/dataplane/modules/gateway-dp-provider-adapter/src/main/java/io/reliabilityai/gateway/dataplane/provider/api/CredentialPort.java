package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import java.util.Optional;

/**
 * The credential seam (Doc 25 §33.1 CR-1..7, PA-D8). The adapter <b>consumes short-lived credential
 * material</b> supplied by the existing Secrets mechanism (C14 cached snapshot, Doc 06 §9.6,
 * AD-022) — it <b>owns no credential store and persists nothing</b> (CR-1/CR-2). A miss/expiry ⇒
 * <b>fail closed</b> ({@code auth_failed}), never a bypass (CR-6, Doc 06 §9.6). No synchronous
 * control-plane call is made on the hot path (CR-3, AD-022).
 *
 * <p>The returned {@link CredentialLease} is single-use and zeroized on close; the adapter applies
 * it within a bounded scope at the transport call and never copies the material out (CR-4).
 */
public interface CredentialPort {

  /**
   * Acquires the short-lived credential lease for the given route (Doc 25 §33.1 CR-3).
   *
   * @param routeTarget the selected route target
   * @return the credential lease, or empty ⇒ fail closed ({@code auth_failed}, CR-6)
   */
  Optional<CredentialLease> acquire(RouteTarget routeTarget);
}
