package io.reliabilityai.gateway.dataplane.observability.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces the Plane-A metric-label policy as a <b>deny-by-default allow-list</b> (Doc 14 §7.1
 * OH-2, Doc 27 OT-A6/OT-D6): <b>only</b> the explicitly-registered low-cardinality label keys may
 * be emitted; <em>any</em> key not on the allow-list is dropped — including content-bearing keys
 * the pattern redaction scanner cannot catch (e.g. {@code raw_prompt}, {@code completion}). The
 * permitted set is Doc-14-owned (REA-6) and injected. A small <b>frozen forbidden</b> set
 * (correlation/request/trace/user ids, api keys, email, …) is additionally rejected as
 * belt-and-suspenders even if it were mistakenly registered. A violating metric is dropped (never
 * emitted to Plane A), never mutated.
 */
public final class MetricLabelPolicy {

  private static final Set<String> FORBIDDEN_LABEL_KEYS =
      Set.of(
          "correlation_id",
          "correlationid",
          "request_id",
          "requestid",
          "trace_id",
          "traceid",
          "span_id",
          "user_id",
          "userid",
          "api_key",
          "apikey",
          "session_id",
          "sessionid",
          "email",
          "idempotency_key",
          "attempt_id",
          "raw_prompt",
          "prompt",
          "completion");

  private final Set<String> allowedKeys;

  /**
   * Creates the policy over the Doc-14-registered permitted label keys (Doc 14 §7.1) —
   * deny-by-default.
   *
   * @param allowedKeys the explicitly-permitted low-cardinality label keys (case-insensitive)
   */
  public MetricLabelPolicy(final Set<String> allowedKeys) {
    Preconditions.requireNonNull(allowedKeys, "allowedKeys");
    this.allowedKeys =
        allowedKeys.stream()
            .map(k -> Preconditions.requireNonBlank(k, "allowedKey").toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Returns whether every label key is explicitly permitted (deny-by-default) and none is
   * forbidden.
   *
   * @param labels the metric labels
   * @return {@code true} iff every label key is on the allow-list and not on the forbidden list
   */
  public boolean isAllowed(final Map<String, String> labels) {
    Preconditions.requireNonNull(labels, "labels");
    for (final String key : labels.keySet()) {
      if (key == null) {
        return false; // a null key is never permitted
      }
      final String normalized = key.toLowerCase(Locale.ROOT);
      if (FORBIDDEN_LABEL_KEYS.contains(normalized) || !allowedKeys.contains(normalized)) {
        return false; // deny-by-default: only registered, non-forbidden keys pass
      }
    }
    return true;
  }
}
