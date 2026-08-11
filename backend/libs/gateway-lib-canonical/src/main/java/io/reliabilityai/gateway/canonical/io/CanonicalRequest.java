package io.reliabilityai.gateway.canonical.io;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Map;

/**
 * The provider-neutral request handed to the adapter (Doc 25 §6, Doc 33 §10.2). Produced upstream;
 * keyed by {@link CanonicalModelId}. Content-bearing and never persisted (Doc 33 §10.2). Immutable
 * — lists/maps are defensively copied (Doc 11 R-005).
 *
 * @param canonicalModelId the target canonical model (Doc 19 §9.2)
 * @param messages the conversation messages
 * @param toolDefinitions the available tool definitions
 * @param params provider-neutral parameters
 */
public record CanonicalRequest(
    CanonicalModelId canonicalModelId,
    List<Message> messages,
    List<ToolDefinition> toolDefinitions,
    Map<String, String> params) {

  /** Compact constructor validating the model id and defensively copying collections. */
  public CanonicalRequest {
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    // Inlined copies, not Preconditions.immutableList/Map — see their javadoc (EI_EXPOSE_REP).
    messages = messages == null ? List.of() : List.copyOf(messages);
    toolDefinitions = toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
    params = params == null ? Map.of() : Map.copyOf(params);
  }
}
