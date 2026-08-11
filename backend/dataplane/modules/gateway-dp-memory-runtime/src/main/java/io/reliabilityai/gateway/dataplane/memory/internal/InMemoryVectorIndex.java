package io.reliabilityai.gateway.dataplane.memory.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.VectorIndexPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The reference similarity index: exact cosine over an in-memory map.
 *
 * <p><b>Not a vector database.</b> It scans every entry in the scope, which is exactly what a real
 * index exists to avoid. It is here so the port's contract is executable and the read pipeline can
 * be tested against something that behaves like an index, not so it can serve production traffic.
 *
 * <p>The similarity arithmetic lives here rather than in the runtime on purpose. MEM-2 forbids
 * vector arithmetic in the Memory Runtime, and an adapter is precisely where it belongs — a real
 * adapter delegates it to a database that does it far better.
 *
 * <p>Entries are keyed by scope, so a query cannot reach another tenant's vectors even by accident.
 */
public final class InMemoryVectorIndex implements VectorIndexPort {

  private record Entry(MemoryScope scope, MemoryRecordId id, float[] embedding) {}

  private final Map<String, Entry> entries = new ConcurrentHashMap<>();
  private final AtomicBoolean available = new AtomicBoolean(true);

  /**
   * Makes every subsequent operation report unavailability.
   *
   * <p>Lets the tests exercise the degradation rules: a hybrid query falls back to keyword and says
   * so, while a semantic query refuses (AD-026 §11).
   *
   * @param up whether the index should respond
   */
  public void setAvailable(final boolean up) {
    available.set(up);
  }

  @Override
  public void index(final MemoryScope scope, final MemoryRecordId id, final float[] embedding) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    Preconditions.requireNonNull(embedding, "embedding");
    requireAvailable("index");
    // Copied on the way in. An adapter that retained the caller's array would let a later mutation
    // of
    // that array silently change what is indexed.
    entries.put(key(scope, id), new Entry(scope, id, embedding.clone()));
  }

  @Override
  public List<Similarity> similar(
      final MemoryScope scope,
      final Set<MemoryType> types,
      final float[] embedding,
      final int limit) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(types, "types");
    Preconditions.requireNonNull(embedding, "embedding");
    requireAvailable("similar");

    final List<Similarity> matches = new ArrayList<>();
    for (final Entry entry : entries.values()) {
      if (!entry.scope().visibleTo(scope)) {
        continue;
      }
      matches.add(new Similarity(entry.id(), cosine(embedding, entry.embedding())));
    }
    matches.sort(
        Comparator.comparingDouble(Similarity::score)
            .reversed()
            .thenComparing(similarity -> similarity.id().value()));
    return matches.size() <= limit ? List.copyOf(matches) : List.copyOf(matches.subList(0, limit));
  }

  @Override
  public void remove(final MemoryScope scope, final MemoryRecordId id) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    if (!available.get()) {
      throw new MemoryStoreUnavailableException("in-memory index marked unavailable: remove");
    }
    // Idempotent, and called even for records that were never indexed — an index entry that
    // outlives
    // the record it pointed at is worse than one that never existed (AD-026 §11.2).
    entries.remove(key(scope, id));
  }

  /**
   * Returns how many embeddings are held.
   *
   * @return the entry count
   */
  public int size() {
    return entries.size();
  }

  /** Forgets everything. */
  public void clear() {
    entries.clear();
  }

  /**
   * Cosine similarity, mapped into {@code [0,1]}.
   *
   * <p>Raw cosine runs from -1 to 1; a negative similarity fed to a ranker that clamps at zero
   * would make every dissimilar vector indistinguishable. Rescaling keeps the ordering and gives
   * the ranker a usable range.
   *
   * <p>Mismatched dimensions score zero rather than throwing. A dimension change means the index
   * holds vectors from two embedding generations, which is an operational problem to fix, not a
   * reason to fail every query touching an old record.
   */
  private static double cosine(final float[] left, final float[] right) {
    if (left.length != right.length || left.length == 0) {
      return 0.0d;
    }
    double dot = 0.0d;
    double leftNorm = 0.0d;
    double rightNorm = 0.0d;
    for (int i = 0; i < left.length; i++) {
      dot += (double) left[i] * right[i];
      leftNorm += (double) left[i] * left[i];
      rightNorm += (double) right[i] * right[i];
    }
    if (leftNorm == 0.0d || rightNorm == 0.0d) {
      return 0.0d;
    }
    final double cosine = dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    return (cosine + 1.0d) / 2.0d;
  }

  private void requireAvailable(final String operation) {
    if (!available.get()) {
      throw new MemoryStoreUnavailableException("in-memory index marked unavailable: " + operation);
    }
  }

  private static String key(final MemoryScope scope, final MemoryRecordId id) {
    return scope.key() + "|" + id.value();
  }
}
