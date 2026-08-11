package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A read request (AD-026 §6).
 *
 * <p>Every filter here is <b>pushed down to the adapter</b>. The runtime never retrieves broadly
 * and narrows in memory: a broad retrieval has already read the records it was supposed to exclude,
 * and once read they are in this process, in its logs and in its heap.
 *
 * @param scope the scope being asked about; narrowed against the caller's before any adapter sees
 *     it
 * @param types which memory kinds to search; never empty
 * @param mode how to search
 * @param text the query text, required by keyword and hybrid modes
 * @param vector a caller-supplied embedding, which bypasses the embedding port when present
 * @param metadataFilters exact-match predicates, held in deterministic order
 * @param from the inclusive start of the time window, if any
 * @param to the exclusive end of the time window, if any
 * @param limit the most results to return
 * @param includeArchived whether cold-storage records are wanted
 */
public record MemoryQuery(
    MemoryScope scope,
    Set<MemoryType> types,
    RetrievalMode mode,
    Optional<String> text,
    Optional<float[]> vector,
    Map<String, String> metadataFilters,
    Optional<Instant> from,
    Optional<Instant> to,
    int limit,
    boolean includeArchived) {

  /**
   * The largest result set a single query may ask for. An unbounded read is an unbounded
   * allocation.
   */
  public static final int MAX_LIMIT = 500;

  /** The limit used when a caller does not state one. */
  public static final int DEFAULT_LIMIT = 20;

  /**
   * Validates and canonicalises the query.
   *
   * @param scope the requested scope
   * @param types the memory kinds to search
   * @param mode the retrieval mode
   * @param text the query text
   * @param vector a caller-supplied embedding
   * @param metadataFilters the metadata predicates
   * @param from the window start
   * @param to the window end
   * @param limit the result ceiling
   * @param includeArchived whether to include archived records
   */
  public MemoryQuery {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(mode, "mode");
    Preconditions.requireNonNull(text, "text");
    Preconditions.requireNonNull(vector, "vector");
    Preconditions.requireNonNull(from, "from");
    Preconditions.requireNonNull(to, "to");
    Preconditions.requireNonEmpty(Preconditions.requireNonNull(types, "types"), "types");
    types = java.util.Collections.unmodifiableSet(EnumSet.copyOf(types));
    Preconditions.requireNonNull(metadataFilters, "metadataFilters");
    metadataFilters = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(metadataFilters));

    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be within 1.." + MAX_LIMIT + ", was " + limit);
    }
    // A mode whose inputs are missing cannot be served, and discovering that at the adapter would
    // mean
    // a wasted round trip and an error message from the wrong layer.
    if (mode.needsText() && text.filter(value -> !value.isBlank()).isEmpty()) {
      throw new IllegalArgumentException(mode + " requires query text");
    }
    if (mode.needsVector()
        && text.filter(value -> !value.isBlank()).isEmpty()
        && vector.isEmpty()) {
      throw new IllegalArgumentException(mode + " requires query text or a vector");
    }
    if (from.isPresent() && to.isPresent() && !from.get().isBefore(to.get())) {
      throw new IllegalArgumentException("time window start must precede its end");
    }
  }

  /**
   * A scope-only query over one memory type.
   *
   * @param scope the scope to search
   * @param type the memory kind
   * @return the query
   */
  public static MemoryQuery ofScope(final MemoryScope scope, final MemoryType type) {
    return new MemoryQuery(
        scope,
        EnumSet.of(type),
        RetrievalMode.SCOPE,
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Optional.empty(),
        DEFAULT_LIMIT,
        false);
  }

  /**
   * A keyword query over one memory type.
   *
   * @param scope the scope to search
   * @param type the memory kind
   * @param text the query text
   * @return the query
   */
  public static MemoryQuery ofKeyword(
      final MemoryScope scope, final MemoryType type, final String text) {
    return new MemoryQuery(
        scope,
        EnumSet.of(type),
        RetrievalMode.KEYWORD,
        Optional.of(text),
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Optional.empty(),
        DEFAULT_LIMIT,
        false);
  }

  /**
   * Returns this query with its scope replaced by a narrowed one.
   *
   * <p>The only way the pipeline rewrites a query, and it can only tighten (MEM-15).
   *
   * @param narrowed the narrowed scope
   * @return the query against the narrowed scope
   */
  public MemoryQuery withScope(final MemoryScope narrowed) {
    Preconditions.requireNonNull(narrowed, "narrowed");
    return new MemoryQuery(
        narrowed, types, mode, text, vector, metadataFilters, from, to, limit, includeArchived);
  }

  /**
   * Returns this query with its mode replaced.
   *
   * <p>Used only to degrade {@code HYBRID} to {@code KEYWORD} when the vector path is unavailable,
   * and the degradation is reported in the result rather than hidden (AD-026 §11).
   *
   * @param degraded the mode to fall back to
   * @return the degraded query
   */
  public MemoryQuery withMode(final RetrievalMode degraded) {
    Preconditions.requireNonNull(degraded, "degraded");
    return new MemoryQuery(
        scope, types, degraded, text, vector, metadataFilters, from, to, limit, includeArchived);
  }

  /**
   * Reports whether a record's write instant falls inside this query's time window.
   *
   * <p>Half-open: inclusive of {@code from}, exclusive of {@code to}. A closed window would make
   * two adjacent windows both match a record on the boundary, and paging over adjacent windows
   * would duplicate it.
   *
   * @param instant the instant to test
   * @return true when the instant is inside the window, or when there is no window
   */
  public boolean withinWindow(final Instant instant) {
    Preconditions.requireNonNull(instant, "instant");
    if (from.isPresent() && instant.isBefore(from.get())) {
      return false;
    }
    return to.isEmpty() || instant.isBefore(to.get());
  }

  /**
   * Reports whether a record's metadata satisfies every filter.
   *
   * @param metadata the record's metadata
   * @return true when all filters match
   */
  public boolean matchesMetadata(final Map<String, String> metadata) {
    Preconditions.requireNonNull(metadata, "metadata");
    for (final Map.Entry<String, String> filter : metadataFilters.entrySet()) {
      if (!filter.getValue().equals(metadata.get(filter.getKey()))) {
        return false;
      }
    }
    return true;
  }
}
