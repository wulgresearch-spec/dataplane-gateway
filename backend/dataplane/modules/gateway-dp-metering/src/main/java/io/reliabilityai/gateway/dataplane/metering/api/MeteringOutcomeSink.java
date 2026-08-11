package io.reliabilityai.gateway.dataplane.metering.api;

import io.reliabilityai.gateway.dataplane.metering.domain.RequestUsageManifest;
import io.reliabilityai.gateway.dataplane.metering.domain.UnrecordedReason;

/**
 * The content-free metering-outcome seam (Doc 23 §34/§35/§44). Fans out to Audit (C10, WORM
 * tamper-evident) and Observability (C9) — all <b>content-free</b>: counts, classes, versions;
 * never prompt/completion/raw tokens or secrets (Doc 23 §43, UME-A11-adjacent). A no-op default
 * lets composition omit it. Best-effort: never alters a metering outcome.
 */
public interface MeteringOutcomeSink {

  /** A no-op sink (safe default). */
  MeteringOutcomeSink NO_OP = new MeteringOutcomeSink() {};

  /**
   * Records a fail-closed unrecorded outcome — a surfaced accounting incident (Doc 23 §38/§45).
   *
   * @param reason the neutral unrecorded reason
   */
  default void onUnrecorded(final UnrecordedReason reason) {}

  /**
   * Records a finalized request manifest — a completeness assertion (Doc 23 §17.1/§45).
   *
   * @param manifest the request usage manifest
   */
  default void onFinalized(final RequestUsageManifest manifest) {}
}
