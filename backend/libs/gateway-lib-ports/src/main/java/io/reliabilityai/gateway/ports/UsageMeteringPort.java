package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.usage.UsageFact;

/**
 * The Usage Metering port (C5, Doc 23 §7). Records per-attempt usage facts (WAL-committed, RPO=0,
 * exactly-once) and finalizes the request. Never fabricates, duplicates, or silently drops usage
 * (Doc 23 UME-INV).
 */
public interface UsageMeteringPort {

  /**
   * Records a per-attempt usage fact (idempotency-keyed, WAL-committed; Doc 23 §D3/§39.1).
   *
   * @param fact the immutable usage fact
   */
  void recordAttempt(UsageFact fact);

  /**
   * Finalizes the request after all authoritative usage facts are durably WAL-committed, emitting
   * the {@code RequestFinalized} completeness manifest (Doc 23 §17.1). Never finalizes complete
   * before all facts are recorded (Doc 23 FC-1..12).
   *
   * @param requestId the request id to finalize
   */
  void finalizeRequest(RequestId requestId);
}
