package io.reliabilityai.gateway.dataplane.memory.crypto;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Production cryptography for memory content: AES-256-GCM envelope encryption (AD-028, closes B24).
 *
 * <p>Each record gets its own randomly generated data key. The body is sealed under that data key;
 * the data key is then sealed under a versioned master key held by a {@link MasterKeyProvider}.
 * Both operations authenticate the same additional data, which binds the format, the key version
 * and the <em>scope</em> the content belongs to.
 *
 * <p><b>What the scope binding buys.</b> A ciphertext lifted from one tenant's storage and pasted
 * into another's fails the tag check, because the scope it was sealed against is no longer the
 * scope it is being opened against. The same holds for workspace, owner and partition. Cross-tenant
 * reads are therefore refused by the mathematics rather than by a filter that someone might one day
 * forget to apply — which matters, because a filter is exactly what the sabotage runs for AD-027
 * proved can be removed without the compiler noticing.
 *
 * <p><b>What it does not buy, and this is the important limitation.</b> {@link
 * MemoryCryptoPort#seal(MemoryScope, String)} receives a scope and a body — no record identity. So
 * the additional data cannot name <em>which</em> record a ciphertext belongs to, and two sealed
 * records inside the same scope can be exchanged for one another without any check here failing.
 * Closing that needs a record identifier in the port signature, which is a Memory Runtime change
 * and out of scope for this milestone. It is recorded as B36 and is the single largest gap in this
 * design.
 *
 * <p><b>Why a fresh data key per record.</b> It costs one extra GCM operation over 32 bytes, and in
 * exchange the nonce-reuse risk on the body vanishes entirely: a repeated body nonce is harmless
 * when no two bodies share a key. The residual risk concentrates on the wrap operation, where a
 * single master key does cover many records — and that is bounded explicitly by {@link
 * #wrapBudget}, below.
 *
 * <p>Instances are immutable except for the primary key version, which rotation swaps atomically.
 * Reads never lock.
 */
public final class AeadMemoryCrypto implements MemoryCryptoPort {

  private static final String TRANSFORMATION = "AES/GCM/NoPadding";

  private static final String ALGORITHM = "AES";

  /** 256-bit data keys. AES-128 would also be defensible; this costs little and argues less. */
  private static final int DATA_KEY_BYTES = 32;

  private static final int TAG_BITS = 128;

  /**
   * How many data keys one master key may wrap before it must be rotated.
   *
   * <p>NIST SP 800-38D bounds random 96-bit nonces under a single key at 2^32 invocations to keep
   * the collision probability negligible. The body nonces are not subject to this because each body
   * has its own key; only the wrap nonces accumulate under the master key, so this is the number
   * that matters. Exceeding it fails the write closed rather than sealing something the standard
   * says is outside its safety argument.
   */
  private static final long DEFAULT_WRAP_BUDGET = 1L << 32;

  /**
   * How many times a colliding nonce is redrawn before the generator is declared broken.
   *
   * <p>One collision is almost certainly the canary's own hash collision, so redrawing is right.
   * Three in a row is not a coincidence under any working generator — it means the random source is
   * returning a constant, and the correct response is to stop writing.
   */
  private static final int NONCE_DRAW_ATTEMPTS = 3;

  /**
   * One GCM cipher per thread, reused across operations.
   *
   * <p>This is here because of a measurement. Constructing a {@code Cipher} per call cost 7.97 of
   * the 8.35 microseconds a 1 KiB seal took, and it barely scaled — eight threads reached 113,000
   * seals per second against 108,000 on one thread. A probe isolating each shared component showed
   * {@code Cipher.getInstance} alone running at 1.98 microseconds single-threaded and achieving
   * only 1.57× of a possible 8× under contention, while the same GCM operation over a per-thread
   * cached cipher ran in 1.66 microseconds and scaled 5.40×.
   *
   * <p><b>Per-thread, never shared.</b> The hazard with cipher reuse is a single instance visible
   * to several threads, where one thread's residual state can surface in another's output. A {@link
   * ThreadLocal} has no such exposure, and {@link Cipher#init} — which every use here calls first —
   * resets the instance completely, so no state survives from the previous operation even when that
   * operation ended in a tag failure.
   *
   * <p>Uses are strictly sequential within a call: seal encrypts the body then wraps the data key,
   * unseal unwraps then decrypts. Nothing here nests, so one instance per thread suffices. The cost
   * is one retained cipher per thread that ever seals or opens content.
   */
  private static final ThreadLocal<Cipher> CIPHERS =
      ThreadLocal.withInitial(
          () -> {
            try {
              return Cipher.getInstance(TRANSFORMATION);
            } catch (final GeneralSecurityException unavailable) {
              throw new IllegalStateException(
                  "AES/GCM/NoPadding is unavailable on this JVM", unavailable);
            }
          });

  private final MasterKeyProvider keys;

  private final CryptoMetricsPort metrics;

  private final SecureRandom random;

  private final NonceCanary canary;

  private final long wrapBudget;

  private final Map<String, AtomicLong> wrapCounts = new ConcurrentHashMap<>();

  /** Swapped atomically by {@link #rotateTo(String)}; read without locking on every seal. */
  private volatile String primaryVersion;

  /**
   * Creates the cipher over a key provider.
   *
   * @param keys custody of the master keys
   * @param metrics where cryptographic events are reported
   */
  public AeadMemoryCrypto(final MasterKeyProvider keys, final CryptoMetricsPort metrics) {
    this(keys, metrics, new ThreadLocalSecureRandom(), DEFAULT_WRAP_BUDGET);
  }

  /**
   * A {@link SecureRandom} that hands each thread its own generator.
   *
   * <p>{@code SecureRandom.nextBytes} is synchronised on the generator, and each seal draws from it
   * three times — a data key and two nonces. Measured in isolation, a shared generator took 0.509
   * microseconds per 12-byte draw single-threaded and <em>degraded</em> to 1.045 under eight
   * threads; a per-thread generator took 0.574 and improved to 0.466. So this is worth roughly 1.7
   * microseconds per seal under contention.
   *
   * <p>It is not, however, what was throttling concurrent sealing — that was {@link #CIPHERS}, and
   * replacing this alone changed the eight-thread figure by less than the machine's run-to-run
   * noise. Recorded plainly because the first hypothesis was wrong and the number that disproved it
   * is more useful than the conclusion.
   *
   * <p>Independently seeded per-thread generators are not a weakening. Each is seeded from the
   * platform entropy source exactly as a shared instance would be, and no output is shared or
   * derived between them.
   */
  private static final class ThreadLocalSecureRandom extends SecureRandom {

    private static final long serialVersionUID = 1L;

    private final transient ThreadLocal<SecureRandom> perThread =
        ThreadLocal.withInitial(SecureRandom::new);

    @Override
    public void nextBytes(final byte[] bytes) {
      perThread.get().nextBytes(bytes);
    }
  }

  /**
   * Creates the cipher with an explicit random source and wrap budget.
   *
   * <p>Both are injectable so the failure paths can actually be tested: a generator that repeats
   * can be handed in to prove the canary fires, and a small budget can be handed in to prove
   * exhaustion fails closed rather than at 2^32 writes, which no test suite is going to reach.
   *
   * @param keys custody of the master keys
   * @param metrics where cryptographic events are reported
   * @param random the source of nonces and data keys
   * @param wrapBudget how many wraps one master key version may perform
   */
  public AeadMemoryCrypto(
      final MasterKeyProvider keys,
      final CryptoMetricsPort metrics,
      final SecureRandom random,
      final long wrapBudget) {
    this.keys = Preconditions.requireNonNull(keys, "keys");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.random = Preconditions.requireNonNull(random, "random");
    if (wrapBudget <= 0) {
      throw new IllegalArgumentException("wrapBudget must be positive");
    }
    this.wrapBudget = wrapBudget;
    this.canary = new NonceCanary();
    this.primaryVersion = keys.primaryVersion();
  }

  @Override
  public MemoryCryptoPort.Sealed seal(final MemoryScope scope, final String plaintext) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(plaintext, "plaintext");

    final String version = primaryVersion;
    final SecretKey master =
        keys.keyFor(version)
            .orElseThrow(
                () ->
                    new MemoryStoreUnavailableException(
                        "master key version " + version + " is not loadable"));
    chargeWrapBudget(version);

    final byte[] additionalData = additionalData(version, scope);
    final byte[] dataKeyBytes = new byte[DATA_KEY_BYTES];
    random.nextBytes(dataKeyBytes);
    final SecretKey dataKey = new SecretKeySpec(dataKeyBytes, ALGORITHM);
    try {
      final byte[] bodyNonce = freshNonce(version);
      final byte[] wrapNonce = freshNonce(version);
      final byte[] body =
          transform(
              Cipher.ENCRYPT_MODE,
              dataKey,
              bodyNonce,
              additionalData,
              plaintext.getBytes(StandardCharsets.UTF_8));
      final byte[] wrappedDataKey =
          transform(Cipher.ENCRYPT_MODE, master, wrapNonce, additionalData, dataKeyBytes);
      final String envelope =
          new CryptoEnvelope(version, wrapNonce, wrappedDataKey, bodyNonce, body).encode();
      metrics.sealed(version, plaintext.length());
      return new MemoryCryptoPort.Sealed(envelope, version);
    } catch (final GeneralSecurityException unusable) {
      // No cause is attached: a provider exception can name the algorithm and the key spec, and
      // that
      // string ends up in a log. The condition, not the detail, is what an operator needs.
      throw new MemoryStoreUnavailableException("memory content could not be sealed");
    } finally {
      Arrays.fill(dataKeyBytes, (byte) 0);
    }
  }

  @Override
  public Optional<String> unseal(
      final MemoryScope scope, final String ciphertext, final String keyRef) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(ciphertext, "ciphertext");
    Preconditions.requireNonNull(keyRef, "keyRef");

    final CryptoEnvelope envelope;
    try {
      envelope = CryptoEnvelope.decode(ciphertext);
    } catch (final MalformedEnvelopeException notAnEnvelope) {
      metrics.openFailed("unknown", CryptoMetricsPort.OpenFailure.MALFORMED_ENVELOPE);
      return Optional.empty();
    }

    // The version is authenticated inside the tag as well, so this check is not what stops a
    // downgrade. It stops something subtler: a stored key reference that has been edited to point
    // at
    // a different version would otherwise select the wrong key and merely fail to authenticate,
    // which
    // reports as tampering rather than as the reference-versus-envelope disagreement it actually
    // is.
    if (!constantTimeEquals(envelope.version(), keyRef)) {
      metrics.openFailed(keyRef, CryptoMetricsPort.OpenFailure.KEY_REF_MISMATCH);
      return Optional.empty();
    }

    final Optional<SecretKey> master = keys.keyFor(envelope.version());
    if (master.isEmpty()) {
      metrics.openFailed(envelope.version(), CryptoMetricsPort.OpenFailure.UNKNOWN_KEY_VERSION);
      return Optional.empty();
    }

    final byte[] additionalData = additionalData(envelope.version(), scope);
    byte[] dataKeyBytes = null;
    try {
      dataKeyBytes =
          transform(
              Cipher.DECRYPT_MODE,
              master.get(),
              envelope.wrapNonce(),
              additionalData,
              envelope.wrappedDek());
      final byte[] body =
          transform(
              Cipher.DECRYPT_MODE,
              new SecretKeySpec(dataKeyBytes, ALGORITHM),
              envelope.dataNonce(),
              additionalData,
              envelope.ciphertext());
      metrics.opened(envelope.version());
      return Optional.of(new String(body, StandardCharsets.UTF_8));
    } catch (final AEADBadTagException | IllegalArgumentException refused) {
      // One branch for every way authentication can fail — modified bytes, a record moved between
      // tenants, the wrong master key. The caller is told only that it did not open. Reporting
      // which
      // of those it was would turn this method into an oracle.
      metrics.openFailed(envelope.version(), CryptoMetricsPort.OpenFailure.AUTHENTICATION_FAILED);
      return Optional.empty();
    } catch (final GeneralSecurityException unusable) {
      metrics.openFailed(envelope.version(), CryptoMetricsPort.OpenFailure.AUTHENTICATION_FAILED);
      return Optional.empty();
    } finally {
      if (dataKeyBytes != null) {
        Arrays.fill(dataKeyBytes, (byte) 0);
      }
    }
  }

  /**
   * Re-seals a body under the current primary key version.
   *
   * <p>Deliberately not on {@link MemoryCryptoPort}. Rotation is an operational concern of whoever
   * holds the keys, and putting it on the port would tell the Memory Runtime that keys have
   * versions — a fact AD-026 §12 keeps out of the runtime on purpose. Callers of this are migration
   * jobs, not the read and write pipelines.
   *
   * <p>Returns empty when the input cannot be opened, so a corrupted or retired-key record is
   * skipped by a migration rather than aborting it.
   *
   * @param scope whose content this is; must be the scope it was sealed under
   * @param ciphertext the existing sealed body
   * @param keyRef the key reference stored beside it
   * @return the re-sealed body and its new key reference, or empty when it could not be opened
   */
  public Optional<MemoryCryptoPort.Sealed> rewrap(
      final MemoryScope scope, final String ciphertext, final String keyRef) {
    final Optional<String> plaintext = unseal(scope, ciphertext, keyRef);
    if (plaintext.isEmpty()) {
      return Optional.empty();
    }
    final MemoryCryptoPort.Sealed resealed = seal(scope, plaintext.get());
    if (!resealed.keyRef().equals(keyRef)) {
      metrics.rewrapped(keyRef, resealed.keyRef());
    }
    return Optional.of(resealed);
  }

  /**
   * Promotes a key version to primary, so subsequent writes use it.
   *
   * <p>Existing records are untouched and stay readable under their own versions; rotation is not
   * re-encryption. Moving them is {@link #rewrap} run over the store, which is a separate,
   * resumable operation precisely because it is O(records) and must not block a rotation.
   *
   * @param version the version to seal new content under
   * @throws KeyUnavailableException when the provider cannot load that version
   */
  public void rotateTo(final String version) {
    Preconditions.requireNonBlank(version, "version");
    if (keys.keyFor(version).isEmpty()) {
      throw new KeyUnavailableException("cannot rotate to unloadable key version " + version);
    }
    final String previous = primaryVersion;
    primaryVersion = version;
    metrics.rotated(previous, version);
  }

  /**
   * The version new content is sealed under.
   *
   * @return the primary key version label
   */
  public String primaryVersion() {
    return primaryVersion;
  }

  /**
   * The versions this cipher can still open.
   *
   * @return the readable key version labels
   */
  public Set<String> readableVersions() {
    return keys.versions();
  }

  /**
   * How many wraps a version has performed in this process.
   *
   * <p><b>Process-local.</b> It resets on restart, so it bounds nonce reuse within one process
   * lifetime and not across the life of the key. See B37.
   *
   * @param version the key version label
   * @return the wrap count observed since this instance was created
   */
  public long wrapsUnder(final String version) {
    final AtomicLong count = wrapCounts.get(version);
    return count == null ? 0L : count.get();
  }

  /**
   * Charges one wrap against a version's nonce budget, refusing once it is spent.
   *
   * @param version the key version about to wrap a data key
   * @throws MemoryStoreUnavailableException when the version has exhausted its budget
   */
  private void chargeWrapBudget(final String version) {
    final long used =
        wrapCounts.computeIfAbsent(version, ignored -> new AtomicLong()).incrementAndGet();
    if (used > wrapBudget) {
      metrics.wrapBudgetExceeded(version);
      throw new MemoryStoreUnavailableException(
          "master key version " + version + " has exhausted its nonce budget and must be rotated");
    }
  }

  /**
   * Draws a nonce that the canary has not recently seen.
   *
   * @param version the key version the nonce will be used under, for reporting
   * @return a fresh 96-bit nonce
   * @throws MemoryStoreUnavailableException when the random source keeps repeating itself
   */
  private byte[] freshNonce(final String version) {
    for (int attempt = 0; attempt < NONCE_DRAW_ATTEMPTS; attempt++) {
      final byte[] nonce = new byte[CryptoEnvelope.NONCE_BYTES];
      random.nextBytes(nonce);
      if (!canary.seenBefore(nonce)) {
        return nonce;
      }
      metrics.nonceCollisionSuspected(version);
    }
    throw new MemoryStoreUnavailableException(
        "the random source repeated a nonce " + NONCE_DRAW_ATTEMPTS + " times; refusing to seal");
  }

  /**
   * Builds the additional authenticated data both GCM operations are bound to.
   *
   * <p>Fields are length-prefixed rather than delimited. A delimiter can be forged by choosing a
   * tenant name that contains it, which would let two different scopes produce identical additional
   * data and quietly re-open the cross-tenant hole this is here to close.
   *
   * @param version the key version label
   * @param scope the scope the content belongs to
   * @return the additional authenticated data
   */
  private static byte[] additionalData(final String version, final MemoryScope scope) {
    final byte[] versionBytes = version.getBytes(StandardCharsets.UTF_8);
    final byte[] scopeBytes = scope.key().getBytes(StandardCharsets.UTF_8);
    return ByteBuffer.allocate(4 + 1 + 4 + versionBytes.length + 4 + scopeBytes.length)
        .putInt(3)
        .put(CryptoEnvelope.FORMAT)
        .putInt(versionBytes.length)
        .put(versionBytes)
        .putInt(scopeBytes.length)
        .put(scopeBytes)
        .array();
  }

  /**
   * Runs one GCM operation.
   *
   * @param mode {@link Cipher#ENCRYPT_MODE} or {@link Cipher#DECRYPT_MODE}
   * @param key the key
   * @param nonce the 96-bit nonce
   * @param additionalData the data to authenticate but not encrypt
   * @param input the bytes to transform
   * @return the transformed bytes
   * @throws GeneralSecurityException when the operation fails, including tag verification
   */
  private static byte[] transform(
      final int mode,
      final SecretKey key,
      final byte[] nonce,
      final byte[] additionalData,
      final byte[] input)
      throws GeneralSecurityException {
    final Cipher cipher = CIPHERS.get();
    cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
    cipher.updateAAD(additionalData);
    return cipher.doFinal(input);
  }

  /**
   * Compares two labels without leaking where they diverge.
   *
   * <p>Key versions are not secrets, so this is belt and braces rather than a load-bearing defence.
   * It costs nothing and removes the need for anyone to reason about whether it mattered.
   *
   * @param left the first label
   * @param right the second label
   * @return true when they are equal
   */
  private static boolean constantTimeEquals(final String left, final String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
