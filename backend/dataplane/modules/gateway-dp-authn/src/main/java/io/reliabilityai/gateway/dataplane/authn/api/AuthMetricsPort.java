package io.reliabilityai.gateway.dataplane.authn.api;

/**
 * Outbound seam for content-free authentication telemetry (Doc 37 §17). All labels are bounded and
 * content-free (Doc 14 §7.1); never a token, claim value, or secret. Side-effect-free toward the
 * authentication decision (Doc 27 OT-A1); implementations MUST be non-blocking.
 */
public interface AuthMetricsPort {

  /** A request was authenticated ({@code authn_decisions_total{outcome=authenticated}}). */
  void authenticated();

  /**
   * A request failed authentication ({@code authn_unauthenticated_total{reason}}).
   *
   * @param reason the bounded, content-free reason code (Doc 37 §14)
   */
  void unauthenticated(String reason);

  /**
   * A tenant scope was resolved post-authentication ({@code authn_tenant_resolved_total}, Doc 37
   * §17).
   */
  void tenantResolved();
}
