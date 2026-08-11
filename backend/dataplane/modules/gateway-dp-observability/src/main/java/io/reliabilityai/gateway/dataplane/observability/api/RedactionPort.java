package io.reliabilityai.gateway.dataplane.observability.api;

import java.util.Map;

/**
 * The frozen classification/redaction policy seam (Doc 27 §4/§15.1, Doc 13 §20, Doc 14 §7.1). Scans
 * a signal's attributes/labels and returns a {@link RedactionVerdict}: {@code EMIT} when clean, or
 * {@code REJECTED} when any residual secret / PII / provider-native / raw-tenant pattern remains
 * (fail-secure drop, Doc 27 PMR-7). The policy is owned by Doc 13/Doc 14 — never authored here.
 */
public interface RedactionPort {

  /**
   * Scans attributes/labels for residual sensitive patterns.
   *
   * @param attributes the attributes/labels to scan
   * @return the redaction verdict
   */
  RedactionVerdict scan(Map<String, String> attributes);
}
