package io.reliabilityai.gateway.dataplane.schemalock.domain;

/**
 * The structured-output elicitation strategy (Doc 17 §14, SL-D2), in safest/highest-fidelity-first
 * preference order. Selected deterministically from (capability, schema, policy); provider identity
 * is never involved (AD-007).
 */
public enum Strategy {
  /**
   * Provider enforces the JSON Schema natively — highest conformance, lowest repair/retry (Doc 17
   * §15).
   */
  NATIVE_SCHEMA,
  /** Schema expressed as a single "respond" tool's parameter schema (Doc 17 §17). */
  TOOL_CALL,
  /**
   * Schema injected into the prompt as instruction — last resort, highest escape risk (Doc 17 §16).
   */
  PROMPT_CONSTRAINED
}
