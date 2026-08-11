package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalToolCall;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.ProviderMeta;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.provider.openai.internal.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps OpenAI responses onto canonical form, and OpenAI status codes onto {@link CanonicalError}
 * (Doc 25 §23.1).
 *
 * <p><b>Provider messages never escape.</b> Every error carries an opaque code of the form {@code
 * openai:<status>} and nothing else. Provider prose can contain prompt fragments, internal host
 * names, or account details; forwarding it would turn an error path into a data-leak path, so it is
 * dropped at this boundary rather than sanitised downstream.
 *
 * <p>Malformed payloads become {@link ErrorCategory#MALFORMED_RESPONSE} rather than exceptions
 * escaping into the pipeline — a provider that breaks its own contract must not be able to crash
 * the gateway.
 */
public final class OpenAiResponseMapper {

  /**
   * Maps a successful chat-completions body to a canonical response.
   *
   * @param body the response body
   * @return the canonical response
   * @throws Json.JsonException if the body is not well-formed or lacks a usable choice
   */
  public CanonicalResponse toCanonicalResponse(final String body) {
    Preconditions.requireNonNull(body, "body");
    final Map<String, Object> root = Json.parseObject(body);
    final List<Object> choices = Json.arrayAt(root, "choices");
    if (choices.isEmpty()) {
      throw new Json.JsonException("no choices");
    }
    if (!(choices.get(0) instanceof Map)) {
      throw new Json.JsonException("bad choice");
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> choice = (Map<String, Object>) choices.get(0);
    final Map<String, Object> message = Json.objectAt(choice, "message");

    final String content = message == null ? null : Json.stringAt(message, "content");
    final List<CanonicalToolCall> toolCalls = toolCalls(message);
    final FinishReason finishReason = finishReason(Json.stringAt(choice, "finish_reason"));

    return new CanonicalResponse(
        content == null ? "" : content,
        toolCalls,
        finishReason,
        usage(Json.objectAt(root, "usage")),
        ProviderMeta.empty());
  }

  /**
   * Maps the provider's usage block. Absent usage is reported as {@link UsageClass#ESTIMATED}
   * zeroes rather than fabricated numbers — metering must be able to tell "nothing reported" from
   * "zero used".
   *
   * @param usage the usage object, or {@code null}
   * @return the canonical usage
   */
  public CanonicalUsage usage(final Map<String, Object> usage) {
    if (usage == null) {
      return new CanonicalUsage(0L, 0L, 0L, 0L, 0L, UsageClass.ESTIMATED);
    }
    final Map<String, Object> promptDetails = Json.objectAt(usage, "prompt_tokens_details");
    return new CanonicalUsage(
        Json.longAt(usage, "prompt_tokens", 0L),
        Json.longAt(usage, "completion_tokens", 0L),
        Json.longAt(Json.objectAt(usage, "completion_tokens_details"), "reasoning_tokens", 0L),
        Json.longAt(promptDetails, "cached_tokens", 0L),
        0L,
        UsageClass.AUTHORITATIVE);
  }

  /**
   * Maps an OpenAI finish reason onto the canonical enum.
   *
   * @param raw the provider value, possibly {@code null}
   * @return the canonical finish reason
   */
  public FinishReason finishReason(final String raw) {
    if (raw == null) {
      return FinishReason.ERROR;
    }
    return switch (raw) {
      case "stop" -> FinishReason.STOP;
      case "length" -> FinishReason.LENGTH;
      case "tool_calls", "function_call" -> FinishReason.TOOL_CALLS;
      case "content_filter" -> FinishReason.CONTENT_FILTER;
      default -> FinishReason.ERROR;
    };
  }

  /**
   * Maps an HTTP status onto a canonical error.
   *
   * <p>The retryable hint is the load-bearing part: it is what the Reliability Engine acts on, and
   * getting it wrong either hammers a provider that is refusing us on purpose (429 vs 401) or gives
   * up on one that would have succeeded. Authentication and request-shape failures are permanent;
   * capacity and timeout failures are transient.
   *
   * @param status the HTTP status code
   * @return the canonical error, carrying only an opaque provider code
   */
  public CanonicalError toCanonicalError(final int status) {
    final String opaque = "openai:" + status;
    return switch (status) {
      case 401 -> new CanonicalError(ErrorCategory.AUTH_FAILED, Boolean.FALSE, opaque, false);
      case 403 -> new CanonicalError(ErrorCategory.AUTH_FAILED, Boolean.FALSE, opaque, false);
      case 404 -> new CanonicalError(ErrorCategory.PROVIDER_REJECTED, Boolean.FALSE, opaque, false);
      case 408 -> new CanonicalError(ErrorCategory.TIMEOUT, Boolean.TRUE, opaque, true);
      case 409 -> new CanonicalError(ErrorCategory.PROVIDER_REJECTED, Boolean.FALSE, opaque, false);
      case 429 -> new CanonicalError(ErrorCategory.RATE_LIMITED, Boolean.TRUE, opaque, true);
      case 500 ->
          new CanonicalError(ErrorCategory.PROVIDER_UNAVAILABLE, Boolean.TRUE, opaque, true);
      case 502 ->
          new CanonicalError(ErrorCategory.PROVIDER_UNAVAILABLE, Boolean.TRUE, opaque, true);
      case 503 ->
          new CanonicalError(ErrorCategory.PROVIDER_UNAVAILABLE, Boolean.TRUE, opaque, true);
      case 504 -> new CanonicalError(ErrorCategory.TIMEOUT, Boolean.TRUE, opaque, true);
      default -> {
        if (status >= 500) {
          yield new CanonicalError(ErrorCategory.PROVIDER_UNAVAILABLE, Boolean.TRUE, opaque, true);
        }
        if (status >= 400) {
          yield new CanonicalError(ErrorCategory.PROVIDER_REJECTED, Boolean.FALSE, opaque, false);
        }
        yield new CanonicalError(ErrorCategory.UNKNOWN, Boolean.FALSE, opaque, false);
      }
    };
  }

  /** The canonical error for a body the provider sent but we cannot parse. */
  CanonicalError malformed() {
    return new CanonicalError(
        ErrorCategory.MALFORMED_RESPONSE, Boolean.FALSE, "openai:malformed-response", false);
  }

  private static List<CanonicalToolCall> toolCalls(final Map<String, Object> message) {
    if (message == null) {
      return List.of();
    }
    final List<CanonicalToolCall> calls = new ArrayList<>();
    for (final Object entry : Json.arrayAt(message, "tool_calls")) {
      if (!(entry instanceof Map)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      final Map<String, Object> call = (Map<String, Object>) entry;
      final Map<String, Object> function = Json.objectAt(call, "function");
      final String name = function == null ? null : Json.stringAt(function, "name");
      final String callId = Json.stringAt(call, "id");
      if (name == null || name.isBlank() || callId == null || callId.isBlank()) {
        continue; // an unusable tool call is dropped, never half-materialized
      }
      final String arguments = Json.stringAt(function, "arguments");
      calls.add(new CanonicalToolCall(name, arguments == null ? "" : arguments, callId));
    }
    return List.copyOf(calls);
  }
}
