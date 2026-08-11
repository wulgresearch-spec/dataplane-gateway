package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * A bounded duplicate-suppression window (Doc 18 §17/§16.1/§33) keyed by the payload hash of the
 * {@link IntegrityCheckpoint}. A within-window exact payload-hash match is treated as a transport
 * duplicate and suppressed <b>once</b> (idempotent); the window is hard-bounded and evicts oldest.
 *
 * <p><b>Honest limit (Doc 18 §16.1):</b> because providers expose no reliable sequence/idempotency
 * identifier, StreamGuard cannot distinguish a transport-level duplicate from legitimately-repeated
 * content by hash alone with certainty. It biases to <em>safety, not silent action</em>:
 * within-window exact matches are suppressed; nothing is ever silently delivered twice, and the
 * ambiguous beyond-window case is failed closed by the caller (never silently dropped).
 * Per-session; not shared (AD-021).
 */
public final class DedupWindow {

  private final int capacity;
  private final Deque<Long> order = new ArrayDeque<>();
  private final Map<Long, Integer> counts = new HashMap<>();

  /**
   * Creates a bounded window (Doc 18 §32 — a hard-bounded operational-baseline size).
   *
   * @param capacity the maximum number of recent payload hashes retained ({@code >= 1})
   */
  public DedupWindow(final int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("dedup window capacity must be >= 1");
    }
    this.capacity = capacity;
  }

  /**
   * Whether the given payload hash is currently within the suppression window (Doc 18 §17).
   *
   * @param payloadHash the payload hash
   * @return {@code true} if a within-window duplicate
   */
  public boolean isDuplicate(final long payloadHash) {
    return counts.containsKey(payloadHash);
  }

  /**
   * Records an accepted payload hash, evicting the oldest when the window is full (Doc 18 §17/§32).
   *
   * @param payloadHash the accepted payload hash
   */
  public void record(final long payloadHash) {
    order.addLast(payloadHash);
    counts.merge(payloadHash, 1, Integer::sum);
    if (order.size() > capacity) {
      final long evicted = order.removeFirst();
      counts.computeIfPresent(evicted, (k, v) -> v == 1 ? null : v - 1);
    }
  }
}
