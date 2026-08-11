/**
 * A durable {@code MemoryStorePort} adapter backed by per-tenant append-only journals (AD-027).
 *
 * <p>This package owns persistence and nothing else. There is no policy here, no ranking, no
 * retrieval strategy, no governance, no classification, no embedding and no TTL decision — all of
 * those belong to C17 and none of them is reachable from this module's public surface.
 *
 * <p>The dependency runs one way: this module depends on the memory runtime, and the memory runtime
 * depends on nothing of ours. That is what keeps C17 storage-neutral while this stays deliberately
 * storage-specific.
 */
package io.reliabilityai.gateway.dataplane.memory.store.journal;
