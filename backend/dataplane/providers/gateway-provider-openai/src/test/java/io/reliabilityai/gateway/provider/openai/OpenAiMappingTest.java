package io.reliabilityai.gateway.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.io.ToolDefinition;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.dataplane.provider.api.TransportException;
import io.reliabilityai.gateway.dataplane.provider.api.TransportRequest;
import io.reliabilityai.gateway.dataplane.provider.api.TransportResponse;
import io.reliabilityai.gateway.dataplane.provider.domain.TransportFailureKind;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import io.reliabilityai.gateway.provider.openai.internal.Json;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Request mapping, response mapping and error mapping — the anti-corruption layer's contract. */
class OpenAiMappingTest {

  private final OpenAiRequestMapper requestMapper = new OpenAiRequestMapper();
  private final OpenAiResponseMapper responseMapper = new OpenAiResponseMapper();
  private final OpenAiTranslator translator =
      new OpenAiTranslator(
          OpenAiConfiguration.defaults().organization() == null
              ? OpenAiConfiguration.defaults()
              : OpenAiConfiguration.defaults(),
          requestMapper,
          responseMapper);

  // ---- request mapping ---------------------------------------------------------------------

  @Test
  void mapsMessagesModelAndSupportedParameters() {
    final String body =
        requestMapper.toChatCompletionsBody(
            OpenAiFixture.request(
                Map.of(
                    OpenAiRequestMapper.PARAM_TEMPERATURE, "0.7",
                    OpenAiRequestMapper.PARAM_TOP_P, "0.9",
                    OpenAiRequestMapper.PARAM_MAX_TOKENS, "256",
                    OpenAiRequestMapper.PARAM_STOP, "END,STOP")),
            OpenAiFixture.capabilities(Set.of()),
            false);

    final Map<String, Object> json = Json.parseObject(body);
    assertThat(json.get("model")).isEqualTo("gpt-4o-mini");
    assertThat(Json.arrayAt(json, "messages")).hasSize(1);
    assertThat(json.get("temperature")).isEqualTo(0.7d);
    assertThat(json.get("top_p")).isEqualTo(0.9d);
    assertThat(json.get("max_tokens")).isEqualTo(256.0d);
    assertThat(Json.arrayAt(json, "stop")).containsExactly("END", "STOP");
    assertThat(json).doesNotContainKey("stream");
  }

  @Test
  void omitsParametersTheCallerDidNotSupply() {
    final String body =
        requestMapper.toChatCompletionsBody(
            OpenAiFixture.request(Map.of()), OpenAiFixture.capabilities(Set.of()), false);

    // Sending provider defaults we did not ask for would silently change behaviour between callers.
    final Map<String, Object> json = Json.parseObject(body);
    assertThat(json.keySet()).containsExactly("model", "messages");
  }

  @Test
  void dropsAMalformedNumericParameterRatherThanForwardingItAsAString() {
    final String body =
        requestMapper.toChatCompletionsBody(
            OpenAiFixture.request(Map.of(OpenAiRequestMapper.PARAM_TEMPERATURE, "hot")),
            OpenAiFixture.capabilities(Set.of()),
            false);

    assertThat(Json.parseObject(body)).doesNotContainKey("temperature");
  }

  @Test
  void enablesJsonModeWhenRequested() {
    final String body =
        requestMapper.toChatCompletionsBody(
            OpenAiFixture.request(Map.of(OpenAiRequestMapper.PARAM_JSON_MODE, "true")),
            OpenAiFixture.capabilities(Set.of()),
            false);

    assertThat(Json.stringAt(Json.objectAt(Json.parseObject(body), "response_format"), "type"))
        .isEqualTo("json_object");
  }

  @Test
  @SuppressWarnings("unchecked")
  void passesToolDefinitionsThroughUnmodified() {
    final CanonicalRequest request =
        new CanonicalRequest(
            OpenAiFixture.MODEL,
            List.of(new Message("user", "hi")),
            List.of(new ToolDefinition("lookup", "{\"type\":\"object\",\"required\":[\"q\"]}")),
            Map.of());

    final String body =
        requestMapper.toChatCompletionsBody(request, OpenAiFixture.capabilities(Set.of()), false);

    final Map<String, Object> tool =
        (Map<String, Object>) Json.arrayAt(Json.parseObject(body), "tools").get(0);
    final Map<String, Object> function = Json.objectAt(tool, "function");
    assertThat(tool.get("type")).isEqualTo("function");
    assertThat(function.get("name")).isEqualTo("lookup");
    // Pass-through means the schema arrives byte-for-byte; the gateway does not rewrite tool
    // schemas.
    assertThat(Json.arrayAt(Json.objectAt(function, "parameters"), "required"))
        .containsExactly("q");
  }

  @Test
  void setsStreamFlagOnlyForStreamingCapability() {
    final String body =
        requestMapper.toChatCompletionsBody(
            OpenAiFixture.request(Map.of()), OpenAiFixture.capabilities(Set.of()), true);

    assertThat(Json.parseObject(body).get("stream")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void mapsEmbeddingsRequests() {
    final String body =
        requestMapper.toEmbeddingsBody(
            OpenAiFixture.request(Map.of()), OpenAiFixture.capabilities(Set.of("embeddings")));

    assertThat(Json.arrayAt(Json.parseObject(body), "input")).containsExactly("hello");
  }

  @Test
  void routesEmbeddingsCapabilityToTheEmbeddingsEndpoint() {
    final TransportRequest out =
        translator.translateOut(
            OpenAiFixture.request(Map.of()),
            OpenAiFixture.capabilities(Set.of("embeddings")),
            OpenAiFixture.API_VERSION);

    assertThat(out.endpointRef()).isEqualTo(OpenAiConfiguration.EMBEDDINGS_PATH);
  }

  @Test
  void neverPlacesCredentialsInTheTransportRequest() {
    final TransportRequest out =
        translator.translateOut(
            OpenAiFixture.request(Map.of()),
            OpenAiFixture.capabilities(Set.of()),
            OpenAiFixture.API_VERSION);

    // The reliability engine may retain this request across attempts — it must never hold a secret.
    assertThat(out.headers()).doesNotContainKey("Authorization");
  }

  @Test
  void mappingIsDeterministic() {
    final CanonicalRequest request =
        OpenAiFixture.request(
            Map.of(
                OpenAiRequestMapper.PARAM_TEMPERATURE, "0.5",
                OpenAiRequestMapper.PARAM_MAX_TOKENS, "64",
                OpenAiRequestMapper.PARAM_STOP, "A,B",
                OpenAiRequestMapper.PARAM_JSON_MODE, "true"));

    final String first =
        requestMapper.toChatCompletionsBody(request, OpenAiFixture.capabilities(Set.of()), false);
    for (int run = 0; run < 50; run++) {
      assertThat(
              requestMapper.toChatCompletionsBody(
                  request, OpenAiFixture.capabilities(Set.of()), false))
          .isEqualTo(first);
    }
  }

  // ---- response mapping --------------------------------------------------------------------

  @Test
  void mapsContentFinishReasonAndUsage() {
    final CanonicalResponse response =
        responseMapper.toCanonicalResponse(
            """
            {"choices":[{"message":{"role":"assistant","content":"hi there"},
             "finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":4,
                      "prompt_tokens_details":{"cached_tokens":3},
                      "completion_tokens_details":{"reasoning_tokens":2}}}
            """);

    assertThat(response.content()).isEqualTo("hi there");
    assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    assertThat(response.usage().prompt()).isEqualTo(11L);
    assertThat(response.usage().completion()).isEqualTo(4L);
    assertThat(response.usage().cached()).isEqualTo(3L);
    assertThat(response.usage().reasoning()).isEqualTo(2L);
    assertThat(response.usage().usageClass()).isEqualTo(UsageClass.AUTHORITATIVE);
  }

  @Test
  void reportsAbsentUsageAsEstimatedRatherThanFabricatingZeroes() {
    final CanonicalResponse response =
        responseMapper.toCanonicalResponse(
            "{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}");

    // Metering must be able to distinguish "provider reported nothing" from "provider reported
    // zero".
    assertThat(response.usage().usageClass()).isEqualTo(UsageClass.ESTIMATED);
  }

  @Test
  void mapsToolCalls() {
    final CanonicalResponse response =
        responseMapper.toCanonicalResponse(
            """
            {"choices":[{"message":{"content":null,"tool_calls":[
               {"id":"call_1","type":"function",
                "function":{"name":"lookup","arguments":"{\\"q\\":\\"x\\"}"}}]},
             "finish_reason":"tool_calls"}]}
            """);

    assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
    assertThat(response.toolCalls()).hasSize(1);
    assertThat(response.toolCalls().get(0).name()).isEqualTo("lookup");
    assertThat(response.toolCalls().get(0).callId()).isEqualTo("call_1");
    assertThat(response.toolCalls().get(0).argumentsRaw()).isEqualTo("{\"q\":\"x\"}");
    assertThat(response.content()).isEmpty();
  }

  @Test
  void malformedBodyBecomesAFailedResultNotAnException() {
    final ProviderInvocationResult result =
        translator.translateIn(
            new TransportResponse.Buffered(
                200, Map.of(), "{not json".getBytes(StandardCharsets.UTF_8)));

    assertThat(result).isInstanceOf(ProviderInvocationResult.Failed.class);
    assertThat(((ProviderInvocationResult.Failed) result).error().category())
        .isEqualTo(ErrorCategory.MALFORMED_RESPONSE);
  }

  @Test
  void successfulBodyWithNoChoicesIsAMappingFailure() {
    final ProviderInvocationResult result =
        translator.translateIn(
            new TransportResponse.Buffered(
                200, Map.of(), "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8)));

    assertThat(result).isInstanceOf(ProviderInvocationResult.Failed.class);
  }

  // ---- error mapping -----------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
    "401, AUTH_FAILED, false",
    "403, AUTH_FAILED, false",
    "404, PROVIDER_REJECTED, false",
    "409, PROVIDER_REJECTED, false",
    "408, TIMEOUT, true",
    "429, RATE_LIMITED, true",
    "500, PROVIDER_UNAVAILABLE, true",
    "502, PROVIDER_UNAVAILABLE, true",
    "503, PROVIDER_UNAVAILABLE, true",
    "504, TIMEOUT, true"
  })
  void mapsStatusCodesToCategoryAndRetryability(
      final int status, final String category, final boolean retryable) {
    final CanonicalError error = responseMapper.toCanonicalError(status);

    assertThat(error.category()).isEqualTo(ErrorCategory.valueOf(category));
    assertThat(error.retryableHint()).isEqualTo(retryable);
    assertThat(error.transientError()).isEqualTo(retryable);
  }

  @Test
  void neverLeaksProviderMessages() {
    final ProviderInvocationResult result =
        translator.translateIn(
            new TransportResponse.Buffered(
                429,
                Map.of(),
                ("{\"error\":{\"message\":\"Rate limit reached for org-SECRET on "
                        + "gpt-4o in project proj_abc123\"}}")
                    .getBytes(StandardCharsets.UTF_8)));

    final CanonicalError error = ((ProviderInvocationResult.Failed) result).error();
    // Provider prose can carry org ids, project ids and prompt fragments — none of it may escape.
    assertThat(error.providerCodeOpaque()).isEqualTo("openai:429");
    assertThat(error.providerCodeOpaque()).doesNotContain("SECRET").doesNotContain("proj_abc123");
  }

  @Test
  void classifiesTransportFailuresWithTlsNonRetryable() {
    assertThat(
            translator
                .classifyTransportFailure(new TransportException(TransportFailureKind.CONNECT, "x"))
                .retryableHint())
        .isTrue();
    assertThat(
            translator
                .classifyTransportFailure(new TransportException(TransportFailureKind.TIMEOUT, "x"))
                .category())
        .isEqualTo(ErrorCategory.TIMEOUT);
    // Retrying past a certificate failure hands a MITM a second attempt.
    assertThat(
            translator
                .classifyTransportFailure(new TransportException(TransportFailureKind.TLS, "x"))
                .retryableHint())
        .isFalse();
  }

  @Test
  void aStreamedBodyBecomesAGuardableStreamingTransport() {
    final TransportResponse.Streamed streamed =
        new TransportResponse.Streamed(
            200,
            Map.of(),
            new OpenAiTransportStream(
                new java.io.ByteArrayInputStream("data: hi\n\n".getBytes(StandardCharsets.UTF_8))));

    final ProviderInvocationResult result = translator.translateIn(streamed);

    // The bytes are handed up still framed, so STREAM_GUARD can verify what the provider sent.
    assertThat(result).isInstanceOf(ProviderInvocationResult.StreamingTransport.class);
    assertThat(((ProviderInvocationResult.StreamingTransport) result).source().framing())
        .isEqualTo(io.reliabilityai.gateway.ports.ProviderTransportStream.Framing.SSE);
  }

  @Test
  void aStreamedBodyThatIsNotAProviderTransportStreamFailsClosed() {
    final ProviderInvocationResult result =
        translator.translateIn(new TransportResponse.Streamed(200, Map.of(), subscriber -> {}));

    // Without the original transport there is nothing StreamGuard could honestly verify.
    assertThat(result).isInstanceOf(ProviderInvocationResult.Failed.class);
  }

  @Test
  void streamingRequiresBothTheCapabilityAndTheCallersRequest() {
    // Capability alone must not turn every request into a stream.
    final TransportRequest capableButNotRequested =
        translator.translateOut(
            OpenAiFixture.request(Map.of()),
            OpenAiFixture.capabilities(Set.of("streaming")),
            OpenAiFixture.API_VERSION);
    assertThat(capableButNotRequested.headers()).containsEntry("Accept", "application/json");

    final TransportRequest requested =
        translator.translateOut(
            OpenAiFixture.request(Map.of(OpenAiRequestMapper.PARAM_STREAM, "true")),
            OpenAiFixture.capabilities(Set.of("streaming")),
            OpenAiFixture.API_VERSION);
    assertThat(requested.headers()).containsEntry("Accept", "text/event-stream");
    // Never gzip a stream: the guard would see compressed bytes and judge them corrupt.
    assertThat(requested.headers()).doesNotContainKey("Accept-Encoding");
    assertThat(capableButNotRequested.headers()).containsKey("Accept-Encoding");
    assertThat(Json.parseObject(new String(requested.body(), StandardCharsets.UTF_8)).get("stream"))
        .isEqualTo(Boolean.TRUE);
  }
}
