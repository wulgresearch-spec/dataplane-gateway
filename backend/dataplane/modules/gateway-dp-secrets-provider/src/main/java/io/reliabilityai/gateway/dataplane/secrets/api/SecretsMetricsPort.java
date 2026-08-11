package io.reliabilityai.gateway.dataplane.secrets.api;

/**
 * Outbound seam for content-free secrets telemetry (Doc 26 §25). All labels are bounded and
 * content-free (Doc 14 §7.1); no provider name, no secret-derived value. Side-effect-free toward
 * the materialization flow (Doc 27 OT-A1). {@code leaseLeakDetected} must remain 0 in production —
 * a non-zero value is a security-severity signal (Doc 26 §26).
 *
 * <p>Implementations MUST be <b>non-blocking</b> (L-5): {@code leaseLeakDetected} may be invoked
 * from the shared {@link java.lang.ref.Cleaner} thread as a leak backstop, so a blocking
 * implementation could stall reclamation of other leaked leases. Emit fire-and-forget counters
 * only.
 */
public interface SecretsMetricsPort {

  /** Signals a successful materialization ({@code sp_materializations_total{outcome=leased}}). */
  void materialized();

  /**
   * Signals a fail-closed unavailable outcome ({@code sp_credential_unavailable_total{reason}}).
   *
   * @param reason the bounded, content-free reason label (Doc 26 §21)
   */
  void credentialUnavailable(String reason);

  /**
   * Signals a sanitization result ({@code sp_sanitization_total{result}}, Doc 26 §17.1 MSC-10).
   *
   * @param complete {@code true} if the material was fully overwritten; {@code false} if partial
   */
  void sanitization(boolean complete);

  /**
   * Signals that a lease was reclaimed without an explicit close — a leak backstop fired ({@code
   * sp_lease_leak_detected_total}, Doc 26 §26). MUST be 0 in production.
   */
  void leaseLeakDetected();
}
