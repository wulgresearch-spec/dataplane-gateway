package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A tool a plugin exposes for execution (Doc 28 §12).
 *
 * <p>The definition is declared in the signed manifest, so the set of tools a plugin can offer is
 * fixed at verification time. A plugin cannot announce a new tool at runtime — that would be an
 * un-vetted capability appearing after the vetting gate.
 *
 * <p>{@code inputSchema} is carried as opaque text. This module neither parses nor validates it:
 * JSON Schema is SchemaLock's domain (Doc 17), and duplicating a validator here would create a
 * second dialect that could disagree with the authoritative one.
 *
 * @param name the tool name, unique within the plugin
 * @param description a human-readable description
 * @param inputSchema the opaque JSON Schema text describing the tool's arguments
 */
public record ToolDefinition(String name, String description, String inputSchema) {

  /** The longest tool name accepted, keeping metric labels bounded (Doc 14 §5). */
  public static final int MAX_NAME_LENGTH = 128;

  /** Compact constructor validating the declared shape. */
  public ToolDefinition {
    Preconditions.requireNonBlank(name, "name");
    if (name.length() > MAX_NAME_LENGTH) {
      throw new IllegalArgumentException("tool name exceeds " + MAX_NAME_LENGTH + " characters");
    }
    Preconditions.requireNonNull(description, "description");
    Preconditions.requireNonNull(inputSchema, "inputSchema");
  }
}
