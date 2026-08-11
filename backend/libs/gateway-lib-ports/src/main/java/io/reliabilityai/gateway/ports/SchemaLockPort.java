package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.decision.SchemaValidationResult;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;

/**
 * The SchemaLock port (C3, Doc 17, AD-002). Validates a canonical response/tool-call payload
 * against the pinned schema and returns a verdict; SchemaLock decides, a validation plugin only
 * contributes a signal (Doc 32 §PEB). Never mutates the payload; fail-closed via the returned
 * violations.
 */
public interface SchemaLockPort {

  /**
   * Validates a canonical response against the pinned schema (Doc 17).
   *
   * @param response the canonical response to validate
   * @return the validation verdict (valid flag + content-free violations)
   */
  SchemaValidationResult validate(CanonicalResponse response);
}
