package io.reliabilityai.gateway.dataplane.embedding.application;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingMetricsPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remembers embeddings so the same text is not paid for twice (AD-030 §6).
 *
 * <p>Embedding is deterministic for a fixed model, which makes it exactly the kind of work a cache
 * should serve. On a memory corpus with any repetition — boilerplate, templates, re-indexing after
 * a restart — this is the difference between a bill and a rounding error.
 *
 * <p><b>The key binds every dimension that can change the right answer or the right to see it</b>,
 * each field length-prefixed. Length-prefixing matters for the same reason it did in AD-028:
 * concatenating fields with a separator lets a cunningly chosen identifier collide with another
 * pair, and here a collision would hand one tenant an embedding computed for another.
 *
 * <p>The bound dimensions, and why each one is load-bearing:
 *
 * <ul>
 *   <li><b>Key version</b> — so a future change to this construction cannot silently reuse entries
 *       built under the old rules. Bumping it invalidates the whole cache, which is the correct and
 *       cheapest response to a key-format change.
 *   <li><b>org, tenant, workspace, project</b> — the full {@code TenantScope}, not just org and
 *       tenant. {@code MemoryScope} (AD-026 §9) declares workspace as an isolation level in its own
 *       right, and binding only the outer two let two workspaces in one tenant share entries.
 *   <li><b>provider</b> — an embedding from provider A and one from provider B are vectors in
 *       different spaces. Without this, repointing a neutral model id at a different vendor — the
 *       exact operation the neutral id exists to make possible — keeps serving the old vendor's
 *       vectors from cache while new writes get the new vendor's, silently mixing two geometries in
 *       one index.
 *   <li><b>model id and dimension</b> — the same neutral id may be reconfigured to a different
 *       width.
 *   <li><b>text</b> — the input itself.
 * </ul>
 *
 * <p><b>The identity fields are in the key even though the vector does not depend on them.</b> The
 * same text under the same model embeds identically for everyone, so a shared cache would be
 * arithmetically correct and still wrong: a cross-tenant hit is an oracle telling tenant B that
 * tenant A has embedded a particular string, observable through the roughly tenfold latency gap
 * between a hit and a miss. Paying for the duplicate work is the cheaper mistake.
 *
 * <p><b>What is still NOT bound, stated plainly: the record owner.</b> {@code MemoryScope} declares
 * a per-principal isolation level, but {@code EmbeddingPort} is {@code float[] embed(String)} and
 * carries no scope at all, so no owner ever reaches this method. Two users inside one workspace
 * therefore share cache entries. Closing it requires a change to a Memory Runtime contract and is
 * recorded as B64.
 *
 * <p><b>The negative cache</b> remembers inputs that failed in ways retrying cannot fix — too long,
 * rejected. Without it, a document that will never embed is re-sent on every write attempt, and the
 * provider charges for every one of those refusals.
 */
public final class EmbeddingCache {

  /**
   * The key-construction version, mixed in first.
   *
   * <p>Bump it whenever the field set or the ordering below changes. Entries built under an older
   * construction then become unreachable rather than being reinterpreted under the new rules, which
   * is the difference between a cold cache and a wrong answer.
   */
  private static final String KEY_VERSION = "emb-k2";

  private final Map<String, Entry> positive = new ConcurrentHashMap<>();

  private final Map<String, Negative> negative = new ConcurrentHashMap<>();

  private final ClockPort clock;

  private final EmbeddingMetricsPort metrics;

  private final Duration ttl;

  private final Duration negativeTtl;

  private final int maxEntries;

  private final AtomicLong evictions = new AtomicLong();

  /**
   * Creates a cache.
   *
   * @param clock the time source
   * @param metrics where hit and miss counts go
   * @param ttl how long a successful embedding stays valid
   * @param negativeTtl how long a known failure is remembered
   * @param maxEntries the entry ceiling
   */
  public EmbeddingCache(
      final ClockPort clock,
      final EmbeddingMetricsPort metrics,
      final Duration ttl,
      final Duration negativeTtl,
      final int maxEntries) {
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.ttl = Preconditions.requireNonNull(ttl, "ttl");
    this.negativeTtl = Preconditions.requireNonNull(negativeTtl, "negativeTtl");
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be positive");
    }
    this.maxEntries = maxEntries;
  }

  /**
   * The cache key for one input.
   *
   * @param tenant whose input it is, in full — org, tenant, workspace and project
   * @param model the model that will serve it, contributing both its id and its width
   * @param provider the provider that will serve it, so a vector cannot outlive its producer
   * @param text the input
   * @return the hex-encoded SHA-256 key
   */
  public static String keyFor(
      final TenantScope tenant,
      final EmbeddingModel model,
      final ProviderId provider,
      final String text) {
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonNull(model, "model");
    Preconditions.requireNonNull(provider, "provider");
    Preconditions.requireNonNull(text, "text");
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable on this JVM", impossible);
    }
    feed(digest, KEY_VERSION);
    feed(digest, tenant.org());
    feed(digest, tenant.tenant());
    // Workspace and project are optional, so each is fed as a presence flag followed by its value.
    // Mapping absent onto the empty string instead would make workspace=null and workspace="" hash
    // identically — two scopes the type system keeps distinct, collapsed into one cache namespace
    // by
    // the key. The flag costs five bytes of digest input and removes the ambiguity entirely.
    feedOptional(digest, tenant.workspace());
    feedOptional(digest, tenant.project());
    feed(digest, provider.value());
    feed(digest, model.id());
    feed(digest, Integer.toString(model.dimension()));
    feed(digest, text);
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Looks up a cached embedding.
   *
   * @param key the cache key
   * @return the embedding, or empty when absent or expired
   */
  public Optional<CanonicalEmbedding> get(final String key) {
    final Entry entry = positive.get(key);
    if (entry == null) {
      metrics.cacheMiss(1);
      return Optional.empty();
    }
    if (clock.now().isAfter(entry.expiresAt)) {
      positive.remove(key, entry);
      metrics.cacheMiss(1);
      return Optional.empty();
    }
    metrics.cacheHit(1);
    return Optional.of(entry.embedding);
  }

  /**
   * Looks up a remembered permanent failure.
   *
   * @param key the cache key
   * @return the failure, or empty when this input is not known to fail
   */
  public Optional<EmbeddingFailure> getNegative(final String key) {
    final Negative entry = negative.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    if (clock.now().isAfter(entry.expiresAt)) {
      negative.remove(key, entry);
      return Optional.empty();
    }
    metrics.negativeCacheHit(1);
    return Optional.of(entry.reason);
  }

  /**
   * Stores an embedding.
   *
   * @param key the cache key
   * @param embedding what to remember
   */
  public void put(final String key, final CanonicalEmbedding embedding) {
    evictIfFull();
    positive.put(key, new Entry(embedding, clock.now().plus(ttl)));
  }

  /**
   * Remembers that an input cannot be embedded.
   *
   * <p>The negative cache exists for inputs that are wrong <em>in themselves</em> — too long for
   * the model, rejected as malformed. Re-sending those on every write attempt pays the provider for
   * each refusal.
   *
   * <p><b>Two classes of failure are excluded, for opposite reasons.</b>
   *
   * <p>Retryable failures — a rate limit, a timeout — are excluded because caching a momentary
   * throttle would turn it into a lasting refusal, which is precisely the wrong response to
   * back-pressure.
   *
   * <p><b>Operator-fixable failures are excluded too, and that is the less obvious case.</b> {@code
   * AUTH_FAILED} is not retryable — replaying a rejected credential is an authentication attack
   * against your own provider — but it is also not a property of the input. An expired key makes
   * every input in flight look permanently unembeddable, and remembering that verdict means the fix
   * (rotating the credential) does not take effect until the negative TTL expires. The operator
   * would see embedding still broken minutes after fixing it, with nothing in the logs to explain
   * why. {@code INTERNAL} is excluded on the same grounds: its cause is by definition unknown, so
   * it cannot be attributed to the input.
   *
   * @param key the cache key
   * @param reason why it failed
   */
  public void putNegative(final String key, final EmbeddingFailure reason) {
    if (reason.retryable() || !attributableToTheInput(reason)) {
      return;
    }
    negative.put(key, new Negative(reason, clock.now().plus(negativeTtl)));
  }

  /**
   * Whether a failure says something about the input rather than about the deployment.
   *
   * @param reason the neutral failure kind
   * @return true when re-sending this input would fail again for the same reason
   */
  private static boolean attributableToTheInput(final EmbeddingFailure reason) {
    return switch (reason) {
      case REJECTED, TOO_LARGE, DIMENSION_MISMATCH -> true;
      // AUTH_FAILED and INTERNAL are non-retryable but fixable without touching the input.
      // BUDGET_EXCEEDED never reaches here: governance refuses before any provider call.
      default -> false;
    };
  }

  /**
   * Removes every entry for one tenant.
   *
   * <p>Keys are hashes, so entries cannot be selected by tenant without keeping a reverse index.
   * This clears everything instead, which is correct but blunt, and is recorded as B60.
   *
   * @param tenant whose entries to drop
   */
  public void invalidateTenant(final TenantScope tenant) {
    Preconditions.requireNonNull(tenant, "tenant");
    positive.clear();
    negative.clear();
  }

  /**
   * How many positive entries are held.
   *
   * @return the entry count
   */
  public int size() {
    return positive.size();
  }

  /**
   * How many negative entries are held.
   *
   * @return the entry count
   */
  public int negativeSize() {
    return negative.size();
  }

  /**
   * How many entries have been evicted for capacity.
   *
   * @return the eviction count
   */
  public long evictions() {
    return evictions.get();
  }

  /**
   * Makes room when the cache is at capacity.
   *
   * <p>Whatever iteration reaches, expired or not — <b>not</b> least-recently-used. Real LRU needs
   * an access-ordered structure behind a lock, and a lock on every cache read would cost more than
   * the duplicate embeddings it saves. The consequence is a lower hit ratio than LRU under a
   * working set larger than the cache; B61.
   *
   * <p><b>Eviction runs down to a low-water mark rather than freeing one slot.</b> Freeing exactly
   * one entry per store sounds tidier and is much worse: at capacity, every single store then walks
   * the map to remove one entry — O(n) work per write, permanently — and under concurrent writers
   * it never catches up, because each writer removes one and adds one while the others are also
   * adding. The cache then settles at whatever high-water mark the race produced instead of at its
   * configured ceiling. Evicting a tenth of the capacity at a time amortises the walk over that
   * many stores and lets a single writer pull an overshoot back down in one pass.
   *
   * <p>The bound is still <b>soft</b>, and deliberately so. There is no lock, so any number of
   * writers can pass the capacity check before any of them stores, and the cache can exceed {@code
   * maxEntries} by roughly the number of concurrent writers. Closing that gap needs a lock on the
   * write path, and a slightly larger cache is cheaper than a contended one. What this does
   * guarantee is that the overshoot is bounded and transient rather than permanent, which is the
   * difference between a memory ceiling and a memory leak.
   */
  private void evictIfFull() {
    if (positive.size() < maxEntries) {
      return;
    }
    final int lowWaterMark = maxEntries - Math.max(1, maxEntries / 10);
    final Iterator<Map.Entry<String, Entry>> iterator = positive.entrySet().iterator();
    // size() is re-read each turn rather than tracked locally: a local count and the live size
    // disagree the moment another thread stores, and it was that disagreement that made the
    // previous
    // form stop one entry into an eviction it needed to finish.
    while (iterator.hasNext() && positive.size() > lowWaterMark) {
      iterator.next();
      iterator.remove();
      evictions.incrementAndGet();
    }
  }

  /**
   * Feeds an optional field as a presence flag followed by its length-prefixed value.
   *
   * @param digest the digest
   * @param field the field, or null when absent
   */
  private static void feedOptional(final MessageDigest digest, final String field) {
    digest.update(new byte[] {(byte) (field == null ? 0 : 1)});
    feed(digest, field == null ? "" : field);
  }

  /**
   * Feeds a length-prefixed field into a digest.
   *
   * @param digest the digest
   * @param field the field
   */
  private static void feed(final MessageDigest digest, final String field) {
    final byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
    digest.update(
        new byte[] {
          (byte) (bytes.length >>> 24),
          (byte) (bytes.length >>> 16),
          (byte) (bytes.length >>> 8),
          (byte) bytes.length
        });
    digest.update(bytes);
  }

  /**
   * A cached embedding and its expiry.
   *
   * @param embedding the value
   * @param expiresAt when it stops being valid
   */
  private record Entry(CanonicalEmbedding embedding, Instant expiresAt) {}

  /**
   * A remembered permanent failure and its expiry.
   *
   * @param reason why the input failed
   * @param expiresAt when the memory lapses
   */
  private record Negative(EmbeddingFailure reason, Instant expiresAt) {}
}
