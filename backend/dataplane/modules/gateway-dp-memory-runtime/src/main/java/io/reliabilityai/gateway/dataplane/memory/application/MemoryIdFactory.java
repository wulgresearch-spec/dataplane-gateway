package io.reliabilityai.gateway.dataplane.memory.application;

import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryWriteRequest;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryDigest;
import java.time.Instant;

/**
 * Mints record identities.
 *
 * <p>A seam rather than a hard-coded generator, because deployments differ on what an identifier
 * must look like — some want a ULID for index locality, some a UUID, some a hash. None of that is
 * the memory plane's business, and hard-coding one choice would force every adapter to live with
 * it.
 */
@FunctionalInterface
public interface MemoryIdFactory {

  /**
   * The default: an identity derived deterministically from the write itself.
   *
   * <p><b>Deterministic on purpose.</b> The same scope, type and write key always produce the same
   * id, so a retried write after a lost acknowledgement lands on the same record rather than
   * creating a duplicate. Idempotence is thereby a property of the identity, not only of the store
   * — which matters because it holds even for an adapter whose idempotence support is weak.
   *
   * <p>The creation instant is deliberately <em>not</em> part of the derivation: including it would
   * make a retry a second later a different record, which is exactly the case idempotence exists to
   * handle.
   */
  MemoryIdFactory DETERMINISTIC =
      (request, now) -> MemoryRecordId.of("m-" + MemoryDigest.of(request.idempotencyKey()));

  /**
   * Mints an identity for a write.
   *
   * @param request the write being stored
   * @param now the creation instant
   * @return the record identity
   */
  MemoryRecordId next(MemoryWriteRequest request, Instant now);
}
