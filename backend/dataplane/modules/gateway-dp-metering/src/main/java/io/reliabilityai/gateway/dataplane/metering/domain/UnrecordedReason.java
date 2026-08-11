package io.reliabilityai.gateway.dataplane.metering.domain;

/**
 * The neutral reason a usage fact could not be recorded (Doc 23 §38). Every value drives a
 * fail-closed {@code UsageUnrecorded} outcome — usage is never lost, duplicated, fabricated,
 * estimated, or silently discarded (UME-INV). Content-free.
 */
public enum UnrecordedReason {
  /** No authoritative usage was reported — never fabricate/estimate (Doc 23 §D5/§24). */
  MISSING_USAGE,
  /**
   * Usage was provider-flagged estimated and policy does not permit an estimated fact (Doc 23 §24).
   */
  ESTIMATED_NOT_PERMITTED,
  /** No usage descriptor/version available — cannot normalize authoritatively (Doc 23 §38). */
  MISSING_DESCRIPTOR,
  /** Ambiguous delivered/attempt state — never emit a possibly-double/missing fact (Doc 23 §38). */
  AMBIGUOUS_DELIVERED,
  /**
   * Durable WAL capture failed/saturated — fail-safe reject, never silent drop (Doc 23 §39.1
   * UC-12).
   */
  DURABLE_CAPTURE_FAILED,
  /** Any unknown/internal error — fail closed (Doc 23 §38). */
  INTERNAL_ERROR
}
