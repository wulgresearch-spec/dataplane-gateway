package io.reliabilityai.gateway.canonical.plugin;

/**
 * The frozen additive-only plugin extension points (Doc 28 §EPC, Doc 06 §9, Doc 24A). This is the
 * <b>complete and closed</b> set; no additional extension point may be introduced without a future
 * ADR (Doc 28 EPC-2/EPC-6). Response-transform / provider-interception / stream-mutation are
 * explicitly forbidden (Doc 28 EPC-3).
 */
public enum ExtensionPoint {
  /**
   * Before routing; contributes routing signals to C1 (Doc 19). Optimization lives here (Doc 24A).
   */
  PRE_ROUTING,
  /** Before governance; contributes a classification signal to C4 (Doc 21). */
  CLASSIFICATION,
  /** Before the SchemaLock verdict; contributes a validation signal to C3 (Doc 17). */
  VALIDATION,
  /** Around observability emission; content-free telemetry via C9 (Doc 27). */
  TELEMETRY,
  /** Around metering attribution; contributes attribution metadata to C5 (Doc 23). */
  ATTRIBUTION
}
