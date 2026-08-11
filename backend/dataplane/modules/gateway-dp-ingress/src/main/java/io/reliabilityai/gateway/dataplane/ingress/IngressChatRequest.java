package io.reliabilityai.gateway.dataplane.ingress;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Map;

/**
 * A decoded chat-completions request, handed to the pipeline adapter (Doc 30).
 *
 * <p>Already validated and already content-bounded: the server has checked the method, content
 * type, body size and JSON shape before constructing one of these. The handler therefore never has
 * to re-validate transport concerns, and no malformed input can reach the pipeline.
 *
 * @param model the requested canonical model id
 * @param messages the conversation, in order
 * @param params the supported generation parameters, already normalized to canonical keys
 * @param authorization the raw {@code Authorization} header value, or {@code null}
 * @param correlationId the client-supplied or server-assigned correlation id
 * @param idempotencyKey the client-supplied or server-assigned idempotency key
 */
public record IngressChatRequest(
    String model,
    List<IngressMessage> messages,
    Map<String, String> params,
    String authorization,
    String correlationId,
    String idempotencyKey) {

  /**
   * One conversation turn.
   *
   * @param role the message role
   * @param content the message content
   */
  public record IngressMessage(String role, String content) {

    /** Validates the message. */
    public IngressMessage {
      Preconditions.requireNonBlank(role, "role");
      Preconditions.requireNonNull(content, "content");
    }
  }

  /** Validates the decoded request. */
  public IngressChatRequest {
    Preconditions.requireNonBlank(model, "model");
    messages = messages == null ? List.of() : List.copyOf(messages);
    params = params == null ? Map.of() : Map.copyOf(params);
    Preconditions.requireNonBlank(correlationId, "correlationId");
    Preconditions.requireNonBlank(idempotencyKey, "idempotencyKey");
  }
}
