package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * An event emitted by a streaming tool invocation (Doc 28 §30).
 *
 * <p>These events belong to the <b>plugin's own</b> stream. They are never written into a provider
 * response stream: Doc 28 EPC-3 forbids stream mutation and EPC-8 exposes no stream-write
 * capability, so a plugin's stream and the provider's stream are disjoint by construction. A caller
 * consuming plugin events is consuming the plugin, not the model.
 *
 * <p>Every payload is bounded. An unbounded event would let a plugin exhaust host memory one frame
 * at a time, under the deadline, without ever tripping a wall-clock quota.
 */
public sealed interface PluginEvent
    permits PluginEvent.Progress,
        PluginEvent.Token,
        PluginEvent.ToolUpdate,
        PluginEvent.PartialOutput,
        PluginEvent.Failed,
        PluginEvent.Completed {

  /** The largest payload a single event may carry. */
  int MAX_PAYLOAD_LENGTH = 64 * 1024;

  /**
   * Whether this event ends the stream. Exactly one terminal event is emitted per stream.
   *
   * @return true for {@link Failed} and {@link Completed}
   */
  boolean terminal();

  /**
   * Progress toward completion, as a fraction.
   *
   * @param fraction the completed fraction, in [0.0, 1.0]
   * @param stage a short, content-free stage label
   */
  record Progress(double fraction, String stage) implements PluginEvent {
    /**
     * Compact constructor validating and bounding the progress report.
     *
     * <p>{@code fraction} was always range-checked; {@code stage} was only checked for blankness,
     * so a "short, content-free stage label" could in fact be any size a plugin chose. It is
     * bounded now for the same reason as every other payload here: the buffer holds capacity ×
     * event size, and that product is only bounded if the event is.
     */
    public Progress {
      if (!(fraction >= 0.0) || !(fraction <= 1.0)) {
        // Also rejects NaN, which would otherwise pass both comparisons written the naive way.
        throw new IllegalArgumentException("fraction must be within [0.0, 1.0]");
      }
      Preconditions.requireNonBlank(stage, "stage");
      requireBounded(stage);
    }

    @Override
    public boolean terminal() {
      return false;
    }
  }

  /**
   * An incremental output token.
   *
   * @param text the token text
   */
  record Token(String text) implements PluginEvent {
    /** Compact constructor bounding the token. */
    public Token {
      Preconditions.requireNonNull(text, "text");
      requireBounded(text);
    }

    @Override
    public boolean terminal() {
      return false;
    }
  }

  /**
   * A status update about a tool the plugin is running internally.
   *
   * @param toolName the tool the update concerns
   * @param status a short, content-free status label
   */
  record ToolUpdate(String toolName, String status) implements PluginEvent {
    /**
     * Compact constructor validating and bounding the update.
     *
     * <p>Both fields are bounded, not just checked for blankness. This interface states that every
     * payload is bounded, and two variants did not honour it: {@code Token} and {@code
     * PartialOutput} bound theirs and {@code Completed} inherits a 4 MiB bound from {@link
     * ToolResponse}, while this record and {@code Progress.stage} bounded nothing. A plugin
     * emitting a multi-megabyte "status label" would have held capacity × that size in the stream
     * buffer, which is the frame-at-a-time memory exhaustion the bound exists to stop.
     */
    public ToolUpdate {
      Preconditions.requireNonBlank(toolName, "toolName");
      Preconditions.requireNonBlank(status, "status");
      requireBounded(toolName);
      requireBounded(status);
    }

    @Override
    public boolean terminal() {
      return false;
    }
  }

  /**
   * A partial result, complete enough to be useful before the stream ends.
   *
   * @param payload the partial output
   */
  record PartialOutput(String payload) implements PluginEvent {
    /** Compact constructor bounding the payload. */
    public PartialOutput {
      Preconditions.requireNonNull(payload, "payload");
      requireBounded(payload);
    }

    @Override
    public boolean terminal() {
      return false;
    }
  }

  /**
   * The terminal failure event. The stream ends here and nothing further is emitted.
   *
   * @param error the content-free failure description
   */
  record Failed(ToolError error) implements PluginEvent {
    /** Compact constructor validating the terminal. */
    public Failed {
      Preconditions.requireNonNull(error, "error");
    }

    @Override
    public boolean terminal() {
      return true;
    }
  }

  /**
   * The terminal success event, carrying the assembled response.
   *
   * @param response the final tool output
   */
  record Completed(ToolResponse response) implements PluginEvent {
    /** Compact constructor validating the terminal. */
    public Completed {
      Preconditions.requireNonNull(response, "response");
    }

    @Override
    public boolean terminal() {
      return true;
    }
  }

  private static void requireBounded(final String payload) {
    if (payload.length() > MAX_PAYLOAD_LENGTH) {
      throw new IllegalArgumentException("event payload exceeds " + MAX_PAYLOAD_LENGTH);
    }
  }
}
