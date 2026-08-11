package io.reliabilityai.gateway.dataplane.app;

/**
 * The mandatory, non-bypassable data-plane pipeline stages in frozen execution order (Doc 06 §8,
 * Doc 32 §6, AD-018). Every stage MUST be bound for the deployable to activate; a missing stage is
 * a fail-closed startup error, never a silent bypass. Order is significant (enum ordinal = pipeline
 * order).
 */
public enum MandatoryStage {
  /** Transport ingress (Doc 30). */
  INGRESS,
  /** Authentication (C6, Doc 37). */
  AUTHN,
  /** Governance / PEP (C4, Doc 21). */
  GOVERNANCE,
  /** Provider routing (C1, Doc 19). */
  ROUTER,
  /** Reliability / retry / failover (C2, Doc 20). */
  RELIABILITY,
  /**
   * Credential materialization (C14, Doc 26) — immediately before each provider invocation (Doc 32
   * §6).
   */
  SECRETS,
  /** Provider adapter (Doc 25). */
  ADAPTER,
  /**
   * StreamGuard streaming transport integrity (C3, Doc 18) — before SchemaLock (Doc 32 line 74).
   */
  STREAM_GUARD,
  /** SchemaLock response validation (C3, Doc 17). */
  SCHEMA_LOCK,
  /** Usage metering / accounting (Doc 23). */
  METERING,
  /** Cost pricing (Doc 22). */
  COST,
  /** Telemetry/audit emitter (C9/C10, Doc 27). */
  EMITTER
}
