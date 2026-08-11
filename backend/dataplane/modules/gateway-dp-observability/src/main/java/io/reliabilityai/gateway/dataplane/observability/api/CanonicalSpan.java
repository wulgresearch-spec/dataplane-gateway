package io.reliabilityai.gateway.dataplane.observability.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * A content-free OpenTelemetry span (Doc 27 §5, Doc 14 §6, AD-011). Spans are sampled
 * deterministically from the correlation id (Doc 27 §19.1 RSD-2) and carry only neutral, bounded
 * attributes — never a prompt, completion, token, secret, credential, provider-native code, or raw
 * tenant value (Doc 27 §15.1). The correlation id threads the span to every other signal (Doc 27
 * OT-D8).
 *
 * @param name the span name ({@code <area>.<operation>}, Doc 14 §6)
 * @param correlationId the correlation id (Doc 27 §9)
 * @param tenantScope the tenant scope, or {@code null} for a pre-authentication span (Doc 32 §SPT)
 * @param traceparent the W3C traceparent, or {@code null} (Doc 12 §8)
 * @param attributes neutral, bounded attributes; defensively copied
 */
public record CanonicalSpan(
    String name,
    CorrelationId correlationId,
    TenantScope tenantScope,
    String traceparent,
    Map<String, String> attributes)
    implements ContentFree {

  /** Compact constructor validating required fields and defensively copying attributes. */
  public CanonicalSpan {
    Preconditions.requireNonBlank(name, "name");
    Preconditions.requireNonNull(correlationId, "correlationId");
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
