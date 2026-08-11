package io.reliabilityai.gateway.dataplane.observability.api;

import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * A content-free operational observation emitted to the event fabric for Plane-B analytics (Doc 27
 * §5, Doc 14 §5.1, Doc 07 §6). It is an <b>observation about runtime execution, never a business
 * event</b> (Doc 27 §EO EO-3/EO-5): the emitter observes business events on the backbone and emits
 * separate, clearly-typed observations — it never re-emits, duplicates, or owns a business topic.
 * Content-free.
 *
 * @param type the observation type (an execution observation, not a business event type)
 * @param correlationId the correlation id (Doc 27 §9)
 * @param causationId the causation id, or {@code null} (Doc 07 §6)
 * @param attributes neutral, bounded attributes; defensively copied
 */
public record CanonicalTelemetryObservation(
    String type,
    CorrelationId correlationId,
    CausationId causationId,
    Map<String, String> attributes)
    implements ContentFree {

  /** Compact constructor validating required fields and defensively copying attributes. */
  public CanonicalTelemetryObservation {
    Preconditions.requireNonBlank(type, "type");
    Preconditions.requireNonNull(correlationId, "correlationId");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
