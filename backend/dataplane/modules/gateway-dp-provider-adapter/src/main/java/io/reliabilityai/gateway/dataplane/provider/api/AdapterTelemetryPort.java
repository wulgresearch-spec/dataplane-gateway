package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;

/**
 * Content-free, provider-neutral, low-cardinality adapter telemetry (Doc 25 §34/§35). Labels are
 * bounded (canonical model id, canonical error category, pinned/snapshot versions) — <b>never</b>
 * prompt / response content, credentials, or provider-native codes/names (§35/§36, AD-007). A no-op
 * default is provided so composition can omit telemetry without a null seam.
 */
public interface AdapterTelemetryPort {

  /** A no-op telemetry sink (safe default). */
  AdapterTelemetryPort NO_OP = new AdapterTelemetryPort() {};

  /**
   * Records that an invocation started against a pinned model/version (Doc 25 §25/§35).
   *
   * @param model the canonical model id
   * @param providerApiVersion the pinned provider API version
   * @param snapshotVersion the pinned capability-snapshot version
   */
  default void invoked(
      final CanonicalModelId model,
      final PinnedVersion providerApiVersion,
      final SnapshotVersion snapshotVersion) {}

  /**
   * Records a successful canonical outcome (Doc 25 §35).
   *
   * @param model the canonical model id
   */
  default void succeeded(final CanonicalModelId model) {}

  /**
   * Records a fail-closed canonical error outcome, keyed on the canonical category (Doc 25
   * §35/§37).
   *
   * @param model the canonical model id
   * @param category the canonical error category (no provider label externally)
   */
  default void failed(final CanonicalModelId model, final ErrorCategory category) {}
}
