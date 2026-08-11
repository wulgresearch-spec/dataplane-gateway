package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.io.ToolDefinition;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.provider.openai.internal.Json;
import java.util.List;
import java.util.Map;

/**
 * Maps a {@link CanonicalRequest} onto the OpenAI chat-completions and embeddings wire formats (Doc
 * 25 §23.1).
 *
 * <p><b>Deterministic by construction.</b> Parameters are emitted in a fixed order and only when
 * the caller actually supplied them, so the same canonical request always produces byte-identical
 * JSON. That is what allows a recorded request to be replayed and compared exactly during an audit.
 *
 * <p>Unknown parameters are <b>dropped, not forwarded</b>. Passing through a parameter the gateway
 * does not model would let a caller reach provider behaviour that governance, routing and cost
 * never saw — so the allow-list below is the whole supported surface.
 */
public final class OpenAiRequestMapper {

  /** Canonical parameter key for sampling temperature. */
  public static final String PARAM_TEMPERATURE = "temperature";

  /** Canonical parameter key for nucleus sampling. */
  public static final String PARAM_TOP_P = "top_p";

  /** Canonical parameter key for the output token ceiling. */
  public static final String PARAM_MAX_TOKENS = "max_tokens";

  /** Canonical parameter key for stop sequences, comma-separated. */
  public static final String PARAM_STOP = "stop";

  /** Canonical parameter key for JSON mode; {@code "true"} selects {@code json_object}. */
  public static final String PARAM_JSON_MODE = "json_mode";

  /**
   * Canonical parameter key requesting a streamed response.
   *
   * <p>Distinct from the model's {@code streaming} capability: the capability says the model
   * <em>can</em> stream, this parameter says the caller <em>asked</em> it to. Conflating the two
   * makes every request to a streaming-capable model a stream, which is not what any caller asked
   * for.
   */
  public static final String PARAM_STREAM = "stream";

  /**
   * Builds the chat-completions request body.
   *
   * @param request the canonical request
   * @param capabilities the resolved capability mapping supplying the provider model id
   * @param streaming whether to request a streamed response
   * @return the JSON body
   */
  public String toChatCompletionsBody(
      final CanonicalRequest request,
      final CapabilityMapping capabilities,
      final boolean streaming) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(capabilities, "capabilities");

    final StringBuilder body = new StringBuilder(256);
    body.append('{');
    body.append("\"model\":").append(Json.quote(capabilities.canonicalModelId().value()));
    body.append(",\"messages\":").append(messages(request.messages()));

    final Map<String, String> params = request.params();
    appendNumber(body, "temperature", params.get(PARAM_TEMPERATURE));
    appendNumber(body, "top_p", params.get(PARAM_TOP_P));
    appendNumber(body, "max_tokens", params.get(PARAM_MAX_TOKENS));
    appendStop(body, params.get(PARAM_STOP));

    if ("true".equals(params.get(PARAM_JSON_MODE))) {
      body.append(",\"response_format\":{\"type\":\"json_object\"}");
    }
    if (!request.toolDefinitions().isEmpty()) {
      body.append(",\"tools\":").append(tools(request.toolDefinitions()));
    }
    if (streaming) {
      body.append(",\"stream\":true");
    }
    return body.append('}').toString();
  }

  /**
   * Builds the embeddings request body.
   *
   * @param request the canonical request whose messages carry the input text
   * @param capabilities the resolved capability mapping supplying the provider model id
   * @return the JSON body
   */
  public String toEmbeddingsBody(
      final CanonicalRequest request, final CapabilityMapping capabilities) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(capabilities, "capabilities");

    final StringBuilder input = new StringBuilder(64).append('[');
    for (int i = 0; i < request.messages().size(); i++) {
      if (i > 0) {
        input.append(',');
      }
      input.append(Json.quote(request.messages().get(i).content()));
    }
    input.append(']');

    return "{\"model\":"
        + Json.quote(capabilities.canonicalModelId().value())
        + ",\"input\":"
        + input
        + "}";
  }

  private static String messages(final List<Message> messages) {
    final StringBuilder array = new StringBuilder(128).append('[');
    for (int i = 0; i < messages.size(); i++) {
      if (i > 0) {
        array.append(',');
      }
      final Message message = messages.get(i);
      array
          .append("{\"role\":")
          .append(Json.quote(message.role()))
          .append(",\"content\":")
          .append(Json.quote(message.content()))
          .append('}');
    }
    return array.append(']').toString();
  }

  /**
   * Tool definitions are forwarded as-is (pass-through only). The gateway does not validate,
   * rewrite or execute tool schemas — argument correctness is C3's concern and execution is the
   * caller's.
   */
  private static String tools(final List<ToolDefinition> definitions) {
    final StringBuilder array = new StringBuilder(128).append('[');
    for (int i = 0; i < definitions.size(); i++) {
      if (i > 0) {
        array.append(',');
      }
      final ToolDefinition tool = definitions.get(i);
      array
          .append("{\"type\":\"function\",\"function\":{\"name\":")
          .append(Json.quote(tool.name()))
          .append(",\"parameters\":")
          .append(tool.schemaJson())
          .append("}}");
    }
    return array.append(']').toString();
  }

  /** Emits a numeric parameter only when present and actually numeric — never a quoted number. */
  private static void appendNumber(
      final StringBuilder body, final String wireName, final String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    final double parsed;
    try {
      parsed = Double.parseDouble(value.trim());
    } catch (final NumberFormatException notNumeric) {
      return; // a malformed parameter is dropped, never forwarded as a string
    }
    body.append(',').append(Json.quote(wireName)).append(':');
    if (parsed == Math.rint(parsed) && !Double.isInfinite(parsed)) {
      body.append((long) parsed);
    } else {
      body.append(parsed);
    }
  }

  private static void appendStop(final StringBuilder body, final String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    final String[] sequences = value.split(",", -1);
    final StringBuilder array = new StringBuilder(32).append('[');
    int emitted = 0;
    for (final String sequence : sequences) {
      if (sequence.isEmpty()) {
        continue;
      }
      if (emitted > 0) {
        array.append(',');
      }
      array.append(Json.quote(sequence));
      emitted++;
    }
    if (emitted == 0) {
      return;
    }
    body.append(",\"stop\":").append(array.append(']'));
  }
}
