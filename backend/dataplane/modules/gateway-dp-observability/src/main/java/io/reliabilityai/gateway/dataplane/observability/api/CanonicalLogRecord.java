package io.reliabilityai.gateway.dataplane.observability.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * A content-free structured log record (Doc 27 §5, Doc 14 §7). The {@code event} is a metadata
 * event name, never a free-text message carrying content; {@code attributes} are neutral and
 * redacted before emission (Doc 27 §15/§15.1). No prompt/completion/token/secret/PII/PHI ever
 * appears (Doc 27 OT-A7).
 *
 * @param level the severity
 * @param event the metadata event name (not a content-bearing message)
 * @param correlationId the correlation id threading this record (Doc 27 OT-D8)
 * @param attributes neutral, redacted attributes; defensively copied
 */
public record CanonicalLogRecord(
    LogLevel level, String event, CorrelationId correlationId, Map<String, String> attributes)
    implements ContentFree {

  /** Compact constructor validating required fields and defensively copying attributes. */
  public CanonicalLogRecord {
    Preconditions.requireNonNull(level, "level");
    Preconditions.requireNonBlank(event, "event");
    Preconditions.requireNonNull(correlationId, "correlationId");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
