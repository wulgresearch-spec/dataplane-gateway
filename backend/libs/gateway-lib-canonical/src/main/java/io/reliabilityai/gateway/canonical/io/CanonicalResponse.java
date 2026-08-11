package io.reliabilityai.gateway.canonical.io;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * The provider-neutral non-streaming response (Doc 25 §6, Doc 33 §10.2). Content-bearing and never
 * persisted. {@code providerMeta} is opaque, adapter-internal provenance only and MUST NOT be
 * typed/parsed/consumed downstream (Doc 25 §7.1, PA-A19). Immutable — collections and the opaque
 * byte array are defensively copied (Doc 11 R-005).
 *
 * @param content the response content (nullable for tool-only responses)
 * @param toolCalls the translated tool calls (raw, validated by Doc 17)
 * @param finishReason the canonical finish reason
 * @param usage the authoritative usage (Doc 18 CV-5)
 * @param providerMeta opaque adapter-internal provenance (Doc 33 §10.2, never null — {@link
 *     ProviderMeta#empty()} when absent; MUST NOT be consumed downstream per PA-A19)
 */
public record CanonicalResponse(
    String content,
    List<CanonicalToolCall> toolCalls,
    FinishReason finishReason,
    CanonicalUsage usage,
    ProviderMeta providerMeta) {

  /** Compact constructor validating required fields and defensively copying collections. */
  public CanonicalResponse {
    Preconditions.requireNonNull(finishReason, "finishReason");
    Preconditions.requireNonNull(usage, "usage");
    Preconditions.requireNonNull(providerMeta, "providerMeta");
    // Inlined copy, not Preconditions.immutableList — see that method's javadoc (EI_EXPOSE_REP).
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }
}
