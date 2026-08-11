package io.reliabilityai.gateway.dataplane.secrets.domain;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.ClockPort;
import java.lang.ref.Cleaner;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The per-invocation, single-use, zeroizable credential lease (Doc 26 §11.1/§17.1, Doc 33 §10.7).
 * Material is held in a mutable {@code char[]} (Doc 26 MSC-1 permits {@code char[]}; never a {@code
 * String}) and is <b>deterministically overwritten</b> on {@link #close()} before the lease becomes
 * inactive (MSC-2). Applied only within the bounded {@link #use(SecretConsumer)} scope and never
 * copied out (Doc 26 §17.1); the lease is not {@link java.io.Serializable} and exposes no raw
 * accessor (Doc 26 §20.1). A lease is inactive once released <b>or once expired</b> (Doc 26 §21):
 * expired material is refused, never applied. Time is read only via {@link ClockPort} (Doc 26 §19).
 *
 * <p><b>Honest guarantee (Doc 26 MSC-5/MSC-9):</b> the {@code char[]} is overwritten
 * deterministically on release; however, on a relocating collector (ZGC, AD-023) the runtime may
 * retain transient heap copies until collection, so absolute erasure of every historical copy is
 * not guaranteed. This is runtime memory hygiene, not cryptographic key destruction (which is
 * C14/KMS-owned).
 *
 * <p>A {@link Cleaner} provides a best-effort backstop: if a lease is reclaimed without an explicit
 * {@link #close()}, the backstop zeroizes the material and reports a leak (Doc 26 §26). Thread-safe
 * for the close/use lifecycle (AD-023).
 */
public final class SanitizableCredentialLease implements CredentialLease {

  private static final Cleaner CLEANER = Cleaner.create();

  private final String leaseId;
  private final TenantScope tenantScope;
  private final Instant notAfter;
  private final ClockPort clock;
  private final Material material;
  private final Cleaner.Cleanable cleanable;

  /**
   * Creates a single-use lease that owns and will zeroize the given material.
   *
   * @param leaseId the lease id
   * @param tenantScope the tenant binding (Doc 26 §D7)
   * @param notAfter the expiry (Doc 26 §D3)
   * @param material the transient material (owned and zeroized by this lease)
   * @param onLeak invoked once if the lease is reclaimed without an explicit close (Doc 26 §26)
   * @param clock the deterministic time seam used to enforce expiry (Doc 26 §19)
   */
  public SanitizableCredentialLease(
      final String leaseId,
      final TenantScope tenantScope,
      final Instant notAfter,
      final char[] material,
      final Runnable onLeak,
      final ClockPort clock) {
    this.leaseId = Preconditions.requireNonBlank(leaseId, "leaseId");
    this.tenantScope = Preconditions.requireNonNull(tenantScope, "tenantScope");
    this.notAfter = Preconditions.requireNonNull(notAfter, "notAfter");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    Preconditions.requireNonNull(material, "material");
    Preconditions.requireNonNull(onLeak, "onLeak");
    this.material = new Material(material, onLeak);
    // The cleanup action (Material) must not reference `this`, or the lease is never collectable.
    this.cleanable = CLEANER.register(this, this.material);
  }

  @Override
  public String leaseId() {
    return leaseId;
  }

  @Override
  public TenantScope tenantScope() {
    return tenantScope;
  }

  @Override
  public Instant notAfter() {
    return notAfter;
  }

  @Override
  public boolean active() {
    return material.isActive() && notExpired();
  }

  @Override
  public void use(final SecretConsumer consumer) {
    Preconditions.requireNonNull(consumer, "consumer");
    if (!notExpired()) {
      // Expired material is never applied (Doc 26 §21, SP-D3).
      throw new IllegalStateException("credential lease is expired");
    }
    material.useOnce(consumer);
  }

  @Override
  public void close() {
    // Explicit release: zeroize now and deregister the backstop (no leak reported).
    material.releaseAndZeroize();
    cleanable.clean();
  }

  private boolean notExpired() {
    return clock.now().isBefore(notAfter);
  }

  /**
   * Mutable, self-contained holder of the secret material and lifecycle state. Kept separate from
   * the enclosing lease so the {@link Cleaner} action never resurrects the lease (standard Cleaner
   * idiom).
   */
  private static final class Material implements Runnable {

    private final char[] bytes;
    private final Runnable onLeak;
    // Lock-free lifecycle (P-1): no monitor is ever held across the user callback, so a blocking
    // consumer cannot pin a virtual-thread carrier (AD-023). Zeroization happens exactly once,
    // whichever of close()/Cleaner wins the CAS on `released`.
    private final AtomicBoolean used = new AtomicBoolean();
    private final AtomicBoolean released = new AtomicBoolean();

    private Material(final char[] bytes, final Runnable onLeak) {
      this.bytes = bytes;
      this.onLeak = onLeak;
    }

    private boolean isActive() {
      return !released.get();
    }

    private void useOnce(final SecretConsumer consumer) {
      if (released.get()) {
        throw new IllegalStateException("credential lease is not active");
      }
      if (!used.compareAndSet(false, true)) {
        // Single-use (Doc 26 CLC-8): the material is applied at most once.
        throw new IllegalStateException("credential lease already used");
      }
      if (released.get()) {
        // A concurrent close() zeroized the material after the use-claim: fail closed, never apply
        // wiped bytes. (Confined single-thread usage never hits this; defensive for misuse.)
        throw new IllegalStateException("credential lease is not active");
      }
      // The callback runs WITHOUT holding any monitor — no virtual-thread pinning, no user code
      // executed under a lock (P-1).
      consumer.accept(bytes);
    }

    private void releaseAndZeroize() {
      if (released.compareAndSet(false, true)) {
        Arrays.fill(bytes, (char) 0);
      }
    }

    /** Cleaner backstop: fires only if the lease was reclaimed without an explicit close. */
    @Override
    public void run() {
      if (released.compareAndSet(false, true)) {
        // close() was never called — this is a leak (Doc 26 §26); zeroize and report.
        Arrays.fill(bytes, (char) 0);
        onLeak.run();
      }
    }
  }
}
