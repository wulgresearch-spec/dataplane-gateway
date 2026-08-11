package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.TransportException;
import io.reliabilityai.gateway.dataplane.provider.api.TransportRequest;
import io.reliabilityai.gateway.dataplane.provider.api.TransportResponse;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.provider.openai.internal.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Liveness probe and model listing, both served by {@code GET /v1/models} (Doc 25).
 *
 * <p>The probe is deliberately an <em>authenticated</em> call. An unauthenticated reachability
 * check would report healthy while every real request failed on a rotated key — the failure mode
 * most worth catching. This runs off the request path; nothing here feeds a routing or reliability
 * decision.
 */
public final class OpenAiHealthCheck {

  /**
   * The outcome of a probe.
   *
   * @param healthy whether the provider answered successfully
   * @param status the HTTP status observed, or {@code 0} when the call never completed
   * @param models the model ids returned, sorted; empty when unhealthy
   * @param detail an opaque, content-free detail code
   */
  public record Health(boolean healthy, int status, List<String> models, String detail) {

    /** Validates the health verdict. */
    public Health {
      models = models == null ? List.of() : List.copyOf(models);
      Preconditions.requireNonBlank(detail, "detail");
    }
  }

  private final OpenAiProviderTransport transport;
  private final OpenAiConfiguration configuration;

  /**
   * Creates the health check.
   *
   * @param transport the shared transport
   * @param configuration the adapter configuration
   */
  public OpenAiHealthCheck(
      final OpenAiProviderTransport transport, final OpenAiConfiguration configuration) {
    this.transport = Preconditions.requireNonNull(transport, "transport");
    this.configuration = Preconditions.requireNonNull(configuration, "configuration");
  }

  /**
   * Probes the provider.
   *
   * @param credential a live credential lease
   * @param budget the transport budget for the probe
   * @return the health verdict — never throws, because a probe that throws is a worse signal than
   *     one that reports unhealthy
   */
  public Health probe(final CredentialLease credential, final AttemptBudget budget) {
    Preconditions.requireNonNull(credential, "credential");
    Preconditions.requireNonNull(budget, "budget");

    final Map<String, String> headers =
        configuration.compressionEnabled()
            ? Map.of("Accept", "application/json", "Accept-Encoding", "gzip")
            : Map.of("Accept", "application/json");
    final TransportRequest request =
        new TransportRequest(
            OpenAiConfiguration.MODELS_PATH, headers, new byte[0], configuration.apiVersion());

    final TransportResponse response;
    try {
      response = transport.exchange(request, credential, budget);
    } catch (final TransportException failure) {
      return new Health(false, 0, List.of(), "transport:" + failure.kind().name());
    }

    if (response.status() < 200 || response.status() >= 300) {
      return new Health(false, response.status(), List.of(), "status:" + response.status());
    }
    if (!(response instanceof TransportResponse.Buffered buffered)) {
      return new Health(false, response.status(), List.of(), "unexpected-stream");
    }
    try {
      return new Health(
          true,
          response.status(),
          models(new String(buffered.body(), StandardCharsets.UTF_8)),
          "ok");
    } catch (final Json.JsonException malformed) {
      return new Health(false, response.status(), List.of(), "malformed-response");
    }
  }

  /**
   * Extracts the model ids from a {@code /v1/models} body.
   *
   * @param body the response body
   * @return the model ids, sorted for deterministic comparison
   */
  public List<String> models(final String body) {
    Preconditions.requireNonNull(body, "body");
    final List<String> ids = new ArrayList<>();
    for (final Object entry : Json.arrayAt(Json.parseObject(body), "data")) {
      if (!(entry instanceof Map)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      final Map<String, Object> model = (Map<String, Object>) entry;
      final String id = Json.stringAt(model, "id");
      if (id != null && !id.isBlank()) {
        ids.add(id);
      }
    }
    ids.sort(String::compareTo);
    return List.copyOf(ids);
  }
}
