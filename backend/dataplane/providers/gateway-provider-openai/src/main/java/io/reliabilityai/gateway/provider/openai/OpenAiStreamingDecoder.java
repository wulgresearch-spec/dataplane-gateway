package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.io.CanonicalToolCall;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import io.reliabilityai.gateway.canonical.stream.StreamState;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.streamguard.api.FramingType;
import io.reliabilityai.gateway.provider.openai.internal.Json;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps one already-deframed OpenAI stream event onto a {@link StreamChunk}.
 *
 * <p><b>This decoder does no framing.</b> Splitting the byte stream into {@code data:} events,
 * handling partial lines, UTF-8 validation, sequencing and duplicate suppression are all
 * StreamGuard's job (Doc 18) — it is the component that proves transport integrity, and a second
 * parser here would be a bypass of that proof. This class receives the payload StreamGuard already
 * extracted and only interprets the JSON inside it.
 *
 * <p>Returns {@link Optional#empty()} for events that carry no canonical meaning (keep-alives,
 * role-only openers), so a caller never has to invent a chunk for them.
 */
public final class OpenAiStreamingDecoder {

  /** The sentinel OpenAI sends to close a stream. */
  public static final String DONE_SENTINEL = "[DONE]";

  private final OpenAiResponseMapper responseMapper;

  /**
   * Creates the decoder.
   *
   * @param responseMapper the shared response mapper, reused so streamed and unary finish reasons
   *     and usage map identically
   */
  public OpenAiStreamingDecoder(final OpenAiResponseMapper responseMapper) {
    this.responseMapper = Preconditions.requireNonNull(responseMapper, "responseMapper");
  }

  /**
   * The framing StreamGuard must use for this provider.
   *
   * @return {@link FramingType#SSE}
   */
  public FramingType framing() {
    return FramingType.SSE;
  }

  /**
   * Decodes one StreamGuard-supplied event payload.
   *
   * @param payload the {@code data:} value StreamGuard extracted, without the field prefix
   * @return the canonical chunk, or empty when the event carries nothing canonical
   * @throws Json.JsonException if the payload is neither the sentinel nor well-formed JSON
   */
  public Optional<StreamChunk> decode(final String payload) {
    Preconditions.requireNonNull(payload, "payload");
    final String trimmed = payload.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    if (DONE_SENTINEL.equals(trimmed)) {
      return Optional.of(
          new StreamChunk.Terminal(StreamState.TERMINAL_COMPLETE, FinishReason.STOP));
    }

    final Map<String, Object> root = Json.parseObject(trimmed);

    // A usage-only frame (stream_options.include_usage) closes the accounting picture.
    final Map<String, Object> usage = Json.objectAt(root, "usage");
    final List<Object> choices = Json.arrayAt(root, "choices");
    if (choices.isEmpty()) {
      return usage == null
          ? Optional.empty()
          : Optional.of(new StreamChunk.Usage(responseMapper.usage(usage)));
    }
    if (!(choices.get(0) instanceof Map)) {
      throw new Json.JsonException("bad choice");
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> choice = (Map<String, Object>) choices.get(0);

    // finish_reason terminates the stream and takes precedence over any trailing delta text.
    final String finish = Json.stringAt(choice, "finish_reason");
    if (finish != null) {
      final FinishReason reason = responseMapper.finishReason(finish);
      return Optional.of(
          new StreamChunk.Terminal(
              reason == FinishReason.ERROR
                  ? StreamState.TERMINAL_FAILED
                  : StreamState.TERMINAL_COMPLETE,
              reason));
    }

    final Map<String, Object> delta = Json.objectAt(choice, "delta");
    if (delta == null) {
      return Optional.empty();
    }

    final Optional<StreamChunk> toolCall = toolCallDelta(delta);
    if (toolCall.isPresent()) {
      return toolCall;
    }

    final String text = Json.stringAt(delta, "content");
    if (text == null || text.isEmpty()) {
      return Optional.empty(); // role-only opener or keep-alive
    }
    return Optional.of(new StreamChunk.Delta(text));
  }

  private static Optional<StreamChunk> toolCallDelta(final Map<String, Object> delta) {
    for (final Object entry : Json.arrayAt(delta, "tool_calls")) {
      if (!(entry instanceof Map)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      final Map<String, Object> call = (Map<String, Object>) entry;
      final Map<String, Object> function = Json.objectAt(call, "function");
      final String name = function == null ? null : Json.stringAt(function, "name");
      final String callId = Json.stringAt(call, "id");
      if (name == null || name.isBlank() || callId == null || callId.isBlank()) {
        continue; // argument fragments without an identity are reassembled by the caller, not here
      }
      final String arguments = Json.stringAt(function, "arguments");
      return Optional.of(
          new StreamChunk.ToolCallDelta(
              new CanonicalToolCall(name, arguments == null ? "" : arguments, callId)));
    }
    return Optional.empty();
  }
}
