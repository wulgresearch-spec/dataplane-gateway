package io.reliabilityai.gateway.dataplane.schemalock.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A bounded, in-process, content-hash-keyed LRU of compiled schemas (Doc 17 §10, SL-D5). Keyed by
 * the {@link SchemaId} content hash — <b>never</b> tenant identity (Doc 17 §10.1) — so entries are
 * immutable, safely shared across virtual threads, and leak nothing tenant-specific. Hard-bounded:
 * a full cache LRU-evicts, never OOMs (Doc 17 §31, SL-A9-adjacent). The lock is short and
 * non-pinning (Doc 17 §32, R-049): it guards only O(1) map operations, never blocking I/O.
 */
public final class CompiledSchemaCache {

  private final int capacity;
  private final Map<SchemaId, CompiledSchema> lru;

  /**
   * Creates a bounded LRU cache (Doc 17 §10 — size from the operational baseline).
   *
   * @param capacity the maximum number of compiled schemas retained ({@code >= 1})
   */
  public CompiledSchemaCache(final int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1");
    }
    this.capacity = capacity;
    this.lru =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<SchemaId, CompiledSchema> eldest) {
            return size() > CompiledSchemaCache.this.capacity;
          }
        };
  }

  /**
   * Looks up a compiled schema by content hash (Doc 17 §10) — O(1).
   *
   * @param schemaId the content-hash id
   * @return the compiled schema if cached
   */
  public synchronized Optional<CompiledSchema> get(final SchemaId schemaId) {
    Preconditions.requireNonNull(schemaId, "schemaId");
    return Optional.ofNullable(lru.get(schemaId));
  }

  /**
   * Inserts a compiled schema, LRU-evicting when full (Doc 17 §10/§31).
   *
   * @param schema the compiled schema
   */
  public synchronized void put(final CompiledSchema schema) {
    Preconditions.requireNonNull(schema, "schema");
    lru.put(schema.schemaId(), schema);
  }

  /**
   * The current number of cached entries (bounded by capacity).
   *
   * @return the entry count
   */
  public synchronized int size() {
    return lru.size();
  }
}
