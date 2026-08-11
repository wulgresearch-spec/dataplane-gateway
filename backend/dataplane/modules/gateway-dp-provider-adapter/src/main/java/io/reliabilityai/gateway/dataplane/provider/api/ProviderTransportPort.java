package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.ports.AttemptBudget;

/**
 * The <b>impure transport shell</b> (Doc 25 §7/§23.1 DET-2) — the <em>only</em> provider-coupled
 * surface (PA-D1). A concrete per-provider adapter binds this to a real provider SDK/HTTP client
 * (isolated inside the adapter package, PA-D11); provider objects never cross this boundary. It
 * performs exactly one provider attempt (Doc 25 §17.1 TO-5) within the handed {@link AttemptBudget}
 * (the adapter enforces the budget, it authors no policy). Credentials are applied
 * <b>per-invocation, at call time</b> via {@link CredentialLease#use} — in process memory only,
 * never bound to a pooled connection (§31.1 PL-3; §33.1 CR-4).
 *
 * <p>Deliberately <b>not implemented in this module</b>: the concrete SDK/HTTP binding requires a
 * real provider client and recorded provider fixtures (Doc 25 §23.1 DET-5). This is the
 * deterministic pure-core / impure-shell split (DET-3): the shell is contract-tested against
 * recorded fixtures, never hand-faked into the neutral core.
 */
public interface ProviderTransportPort {

  /**
   * Executes exactly one provider attempt (Doc 25 §8 transport / §17.1 TO-5).
   *
   * @param request the neutral transport request envelope (translate-out output)
   * @param credential the short-lived credential lease, applied at call time and never retained
   *     (§33.1 CR-4); the transport applies it via {@link CredentialLease#use}
   * @param budget the per-attempt transport budget handed by Reliability (Doc 25 §17.1)
   * @return the neutral transport response envelope
   * @throws TransportException on a transport-layer failure before any provider verdict (Doc 25
   *     §16.1)
   */
  TransportResponse exchange(
      TransportRequest request, CredentialLease credential, AttemptBudget budget)
      throws TransportException;
}
