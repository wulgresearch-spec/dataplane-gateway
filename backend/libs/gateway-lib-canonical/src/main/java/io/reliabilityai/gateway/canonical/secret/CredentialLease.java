package io.reliabilityai.gateway.canonical.secret;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.time.Instant;

/**
 * The per-invocation materialized credential lease (C14, Doc 33 §10.7, Doc 26). Defines the
 * <em>contract</em> only; the zeroizable implementation is owned by the Secrets Provider (Doc 26,
 * built in P1).
 *
 * <p>Sensitivity contract (Doc 26 §17.1/§20.1/§CAB): a lease is <b>single-use</b>, <b>never
 * serialized/persisted/logged/captured</b>, and is <b>zeroized on {@link #close()}</b>. It is
 * deliberately NOT {@link java.io.Serializable} and exposes no getter returning raw secret bytes —
 * material is applied within a bounded scope via {@link #use(SecretConsumer)} and never escapes. It
 * is supplied only to the Provider Adapter (Doc 25 §7) and never retained (Doc 26 §11.1 / CLC-9).
 */
public interface CredentialLease extends AutoCloseable {

  /**
   * The lease id (Doc 26 §6).
   *
   * @return the non-null lease id
   */
  String leaseId();

  /**
   * The tenant scope this lease is bound to (Doc 26 §D7, AD-021).
   *
   * @return the non-null tenant scope
   */
  TenantScope tenantScope();

  /**
   * The expiry after which the lease must not be used (Doc 26 §D3).
   *
   * @return the non-null expiry instant
   */
  Instant notAfter();

  /**
   * Whether the lease is currently active (materialized, not yet released/expired).
   *
   * @return true if active
   */
  boolean active();

  /**
   * Applies the secret material to the given consumer within a bounded scope; the material never
   * escapes the callback and is never copied out (Doc 26 §17.1). May be invoked at most once
   * (single-use, Doc 26 CLC-8).
   *
   * @param consumer the secret consumer (e.g. the adapter applying provider authentication)
   */
  void use(SecretConsumer consumer);

  /**
   * Releases the lease and zeroizes the underlying material (best-effort runtime sanitization, Doc
   * 26 §17.1). Idempotent; after release the lease is inactive and further use throws.
   */
  @Override
  void close();

  /**
   * Consumer of transient secret material within a bounded scope (Doc 26 §17.1). The provided array
   * is owned by the lease, must not be retained, copied, logged, or serialized, and is zeroized
   * when the lease closes.
   */
  @FunctionalInterface
  interface SecretConsumer {
    /**
     * Consumes the transient secret material.
     *
     * @param material the transient secret material (never retained)
     */
    void accept(char[] material);
  }
}
