package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * The arguments handed to one tool call (Doc 28 §15).
 *
 * <p>Arguments are carried as opaque text. This module does not parse them, and deliberately does
 * not validate them against the tool's declared schema: JSON Schema validation belongs to
 * SchemaLock (Doc 17), and a second validator here would eventually disagree with the authoritative
 * one.
 *
 * @param toolName the tool being invoked
 * @param arguments the opaque argument payload
 * @param attributes content-free routing attributes the caller attached
 */
public record ToolRequest(String toolName, String arguments, Map<String, String> attributes) {

  /** The largest argument payload accepted, bounding the memory one call can pin. */
  public static final int MAX_ARGUMENT_BYTES = 1024 * 1024;

  /** Compact constructor validating and bounding the request. */
  public ToolRequest {
    Preconditions.requireNonBlank(toolName, "toolName");
    Preconditions.requireNonNull(arguments, "arguments");
    if (arguments.length() > MAX_ARGUMENT_BYTES) {
      throw new IllegalArgumentException("arguments exceed " + MAX_ARGUMENT_BYTES + " characters");
    }
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /**
   * Creates a request with no extra attributes.
   *
   * @param toolName the tool being invoked
   * @param arguments the opaque argument payload
   * @return the request
   */
  public static ToolRequest of(final String toolName, final String arguments) {
    return new ToolRequest(toolName, arguments, Map.of());
  }
}
