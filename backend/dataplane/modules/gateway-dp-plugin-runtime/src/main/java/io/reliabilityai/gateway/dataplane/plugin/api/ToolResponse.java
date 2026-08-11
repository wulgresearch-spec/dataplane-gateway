package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * What a tool returned (Doc 28 §15).
 *
 * <p>The output is bounded on the way in. A plugin that returns an unbounded string would pin host
 * memory proportional to whatever it decided to produce, which is the memory-exhaustion path Doc 28
 * REC-4 closes — and bounding it at the boundary is cheaper and more certain than trying to account
 * for it afterwards.
 *
 * @param output the opaque tool output
 * @param attributes content-free attributes describing the output
 */
public record ToolResponse(String output, Map<String, String> attributes) {

  /** The largest output accepted from a single tool call. */
  public static final int MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

  /** Compact constructor validating and bounding the response. */
  public ToolResponse {
    Preconditions.requireNonNull(output, "output");
    if (output.length() > MAX_OUTPUT_BYTES) {
      throw new IllegalArgumentException("output exceeds " + MAX_OUTPUT_BYTES + " characters");
    }
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  /**
   * Creates a response with no attributes.
   *
   * @param output the opaque tool output
   * @return the response
   */
  public static ToolResponse of(final String output) {
    return new ToolResponse(output, Map.of());
  }
}
