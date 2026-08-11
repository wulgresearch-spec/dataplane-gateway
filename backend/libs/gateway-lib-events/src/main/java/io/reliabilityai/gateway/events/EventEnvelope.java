package io.reliabilityai.gateway.events;

import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * The standard event envelope (Doc 07 §6). Carries correlation + causation ids so event flows are
 * reconstructable end-to-end and the trace can be rebuilt across the async boundary (Doc 07 §6, Doc
 * 27 §10). Content-free — metadata only; the payload is carried and serialized separately (Avro,
 * Doc 07 §9). Immutable.
 *
 * <p><b>Wire/in-process alignment (M3):</b> the authoritative wire contract is {@code
 * EventEnvelope.avsc} (Doc 07 §9). This record is the in-process representation and maps 1:1 to
 * that schema, field for field, so the two cannot silently drift:
 *
 * <ul>
 *   <li>{@code occurredAt} (Instant) &harr; Avro {@code occurredAtEpochMillis} (long, epoch millis
 *       UTC) — the only representational conversion, performed by the (de)serializer.
 *   <li>{@code tenantScopeToken} (nullable) &harr; Avro optional {@code tenantScopeToken} (id-only,
 *       residency-safe; null before C6 — Doc 27 §10.1, Doc 32 §SPT).
 *   <li>all remaining fields are name- and type-identical.
 * </ul>
 *
 * @param eventId the globally-unique event id (idempotency/dedup anchor)
 * @param eventType the event type (topic-family qualified)
 * @param correlationId the correlation id threading the request (Doc 27 §9)
 * @param causationId the causation id of the triggering event/command
 * @param occurredAt the event occurrence time
 * @param schemaVersion the payload schema major version (Doc 07 §9)
 * @param tenantScopeToken residency-safe id-only tenant scope token, nullable (Doc 27 §10.1)
 */
public record EventEnvelope(
    String eventId,
    String eventType,
    CorrelationId correlationId,
    CausationId causationId,
    Instant occurredAt,
    int schemaVersion,
    String tenantScopeToken)
    implements ContentFree {

  /** Compact constructor validating required metadata and schema version. */
  public EventEnvelope {
    Preconditions.requireNonBlank(eventId, "eventId");
    Preconditions.requireNonBlank(eventType, "eventType");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(causationId, "causationId");
    Preconditions.requireNonNull(occurredAt, "occurredAt");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be >= 1");
    }
    // tenantScopeToken is optional (id-only; null before C6) — no validation beyond nullability.
  }
}
