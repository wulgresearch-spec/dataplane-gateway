package io.reliabilityai.gateway.dataplane.metering.api;

import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.dataplane.metering.domain.ExecutionFact;
import io.reliabilityai.gateway.dataplane.metering.domain.MeteringResult;
import io.reliabilityai.gateway.dataplane.metering.domain.RequestUsageManifest;

/**
 * The Usage Metering Engine inbound port (Doc 23 §7). Records per-attempt usage as immutable,
 * exactly-once, WAL-committed usage facts (fail-closed on any uncertainty, UME-INV) and finalizes
 * the request with a completeness manifest. Never loses, duplicates, fabricates, estimates, or
 * silently discards usage.
 */
public interface UsageMeteringEnginePort {

  /**
   * Records one attempt's runtime execution fact (Doc 23 §7): normalize → durable WAL commit → emit
   * to ledger/quota/cost. Fail-closed to {@code UsageUnrecorded} on any uncertainty (Doc 23 §38).
   *
   * @param fact the runtime execution fact
   * @return the metering result (recorded fact or a surfaced unrecorded outcome)
   */
  MeteringResult meterAttempt(ExecutionFact fact);

  /**
   * Finalizes a request after all its attempt facts are durably recorded (Doc 23 §17.1), emitting
   * the {@code RequestFinalized} completeness manifest. Finalizes complete <b>only</b> when all
   * facts are durably recorded and none were unrecorded/ambiguous; otherwise INCOMPLETE (fail
   * closed, FC-7/FC-12).
   *
   * @param requestId the request id to finalize
   * @return the request usage manifest (complete or incomplete)
   */
  RequestUsageManifest finalizeRequest(RequestId requestId);
}
