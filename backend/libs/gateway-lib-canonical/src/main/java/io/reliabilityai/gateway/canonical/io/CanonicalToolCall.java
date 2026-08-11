package io.reliabilityai.gateway.canonical.io;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A provider-neutral tool call translated by the adapter (Doc 25 §PA-D5). Arguments are raw and
 * <em>unvalidated</em>; validation is SchemaLock's (Doc 17) — the adapter never validates.
 *
 * @param name the tool name
 * @param argumentsRaw the raw, unvalidated arguments (JSON text)
 * @param callId the provider-neutral call id
 */
public record CanonicalToolCall(String name, String argumentsRaw, String callId) {

  /** Compact constructor validating identifiers and raw arguments presence. */
  public CanonicalToolCall {
    Preconditions.requireNonBlank(name, "name");
    Preconditions.requireNonNull(argumentsRaw, "argumentsRaw");
    Preconditions.requireNonBlank(callId, "callId");
  }
}
