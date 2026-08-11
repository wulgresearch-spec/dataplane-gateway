package io.reliabilityai.gateway.canonical.stream;

import io.reliabilityai.gateway.canonical.io.CanonicalToolCall;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * A provider-neutral canonical stream chunk produced by the adapter and guarded by StreamGuard (Doc
 * 25 §11, Doc 18, Doc 33 §10.3). A sealed set of variants; timing is never reproduced on replay
 * (Doc 32 §CRS). Content-bearing and never persisted.
 */
public sealed interface StreamChunk
    permits StreamChunk.Delta, StreamChunk.ToolCallDelta, StreamChunk.Usage, StreamChunk.Terminal {

  /**
   * A content delta chunk.
   *
   * @param text the incremental content
   */
  record Delta(String text) implements StreamChunk {
    /** Compact constructor validating the text presence. */
    public Delta {
      Preconditions.requireNonNull(text, "text");
    }
  }

  /**
   * A tool-call delta chunk (raw, validated by Doc 17).
   *
   * @param toolCall the (partial) canonical tool call
   */
  record ToolCallDelta(CanonicalToolCall toolCall) implements StreamChunk {
    /** Compact constructor validating the tool call presence. */
    public ToolCallDelta {
      Preconditions.requireNonNull(toolCall, "toolCall");
    }
  }

  /**
   * A usage chunk carrying authoritative usage (Doc 18 CV-5).
   *
   * @param usage the canonical usage
   */
  record Usage(CanonicalUsage usage) implements StreamChunk {
    /** Compact constructor validating the usage presence. */
    public Usage {
      Preconditions.requireNonNull(usage, "usage");
    }
  }

  /**
   * The terminal chunk carrying StreamGuard's terminal state and finish reason (Doc 18 §30).
   *
   * @param state the terminal stream state
   * @param finishReason the canonical finish reason
   */
  record Terminal(StreamState state, FinishReason finishReason) implements StreamChunk {
    /** Compact constructor validating the terminal state and finish reason. */
    public Terminal {
      Preconditions.requireNonNull(state, "state");
      Preconditions.requireNonNull(finishReason, "finishReason");
    }
  }
}
