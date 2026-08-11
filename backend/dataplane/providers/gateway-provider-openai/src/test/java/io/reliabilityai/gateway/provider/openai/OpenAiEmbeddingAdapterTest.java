package io.reliabilityai.gateway.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportException;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportPort;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The one vendor embeddings adapter, exercised against a transport double.
 *
 * <p><b>Stated plainly: no OpenAI endpoint is reachable from the environment this was written
 * in.</b> Every path below runs against recorded response <em>shapes</em>, not against the live
 * service. That makes these tests a real check of the adapter's own logic — the escaping, the
 * status mapping, the vector-count guard — and no check at all of whether those shapes match what
 * the API actually sends today. The distinction is recorded as B57 and is not softened here.
 *
 * <p>The transport double lives in this file rather than in {@code OpenAiFixture} because it
 * belongs to the embeddings path only, and a shared fixture that grew a second unrelated protocol
 * would make both harder to read.
 */
@DisplayName("openai embeddings adapter")
final class OpenAiEmbeddingAdapterTest {

  private static final TenantScope ACME = TenantScope.of("acme", "core");
  private static final String MODEL = "text-default-1536";
  private static final int DIMENSION = 1536;

  /** A clock that does not move; nothing here depends on elapsed time. */
  private static final ClockPort CLOCK = () -> Instant.parse("2026-08-07T00:00:00Z");

  private static OpenAiEmbeddingProvider provider(final ScriptedTransport transport) {
    return new OpenAiEmbeddingProvider(transport, () -> "sk-test-key", CLOCK, 5_000L);
  }

  private static EmbeddingRequest request(final String... texts) {
    return new EmbeddingRequest(
        ACME, MODEL, List.of(texts), EmbeddingRequest.EmbeddingPurpose.WRITE);
  }

  /**
   * A transport that replays scripted exchanges, so the adapter can be tested without a network.
   */
  private static final class ScriptedTransport implements EmbeddingTransportPort {

    private final Deque<Object> script = new ArrayDeque<>();
    private final List<Call> seen = new ArrayList<>();

    ScriptedTransport respondWith(final int status, final String body) {
      script.add(new Exchange(status, body.getBytes(StandardCharsets.UTF_8)));
      return this;
    }

    ScriptedTransport failWith(final EmbeddingFailure reason) {
      script.add(reason);
      return this;
    }

    @Override
    public Exchange send(final Call call) {
      seen.add(call);
      final Object next = script.poll();
      if (next == null) {
        throw new EmbeddingTransportException(
            EmbeddingFailure.INTERNAL, "the transport script is exhausted");
      }
      if (next instanceof EmbeddingFailure reason) {
        throw new EmbeddingTransportException(reason, "scripted transport failure");
      }
      return (Exchange) next;
    }
  }

  /**
   * A well-formed provider-native response body.
   *
   * @param vectors the vectors to encode
   * @param promptTokens the usage to report
   * @return the JSON body
   */
  private static String body(final List<float[]> vectors, final int promptTokens) {
    final StringBuilder json = new StringBuilder("{\"object\":\"list\",\"data\":[");
    for (int v = 0; v < vectors.size(); v++) {
      if (v > 0) {
        json.append(',');
      }
      json.append("{\"object\":\"embedding\",\"index\":").append(v).append(",\"embedding\":[");
      final float[] vector = vectors.get(v);
      for (int i = 0; i < vector.length; i++) {
        if (i > 0) {
          json.append(',');
        }
        json.append(vector[i]);
      }
      json.append("]}");
    }
    return json.append("],\"model\":\"text-embedding-3-small\",\"usage\":{\"prompt_tokens\":")
        .append(promptTokens)
        .append(",\"total_tokens\":")
        .append(promptTokens)
        .append("}}")
        .toString();
  }

  /**
   * A vector of the given width with every component set.
   *
   * @param width the width
   * @param value the component value
   * @return the vector
   */
  private static float[] flat(final int width, final float value) {
    final float[] vector = new float[width];
    Arrays.fill(vector, value);
    return vector;
  }

  @Test
  @DisplayName("a well-formed response becomes canonical embeddings")
  void aWellFormedResponseBecomesCanonicalEmbeddings() {
    final ScriptedTransport transport =
        new ScriptedTransport()
            .respondWith(200, body(List.of(flat(DIMENSION, 0.5f), flat(DIMENSION, 0.25f)), 11));

    final EmbeddingResponse response = provider(transport).embed(request("a", "b"));

    assertThat(response.outcomes()).hasSize(2);
    assertThat(response.at(0).orElseThrow().dimension()).isEqualTo(DIMENSION);
    assertThat(response.usage().inputTokens()).isEqualTo(11L);
    assertThat(response.usage().providerCalls()).isEqualTo(1);
  }

  @Test
  @DisplayName("the request carries the vendor model name and the credential")
  void theRequestCarriesTheVendorModelNameAndTheCredential() {
    final ScriptedTransport transport =
        new ScriptedTransport().respondWith(200, body(List.of(flat(DIMENSION, 1f)), 3));

    provider(transport).embed(request("hello"));

    final EmbeddingTransportPort.Call call = transport.seen.get(0);
    // The neutral id goes in; the vendor name comes out. That translation is the whole of the
    // coupling, and it happens here and nowhere else in the system.
    assertThat(new String(call.body(), StandardCharsets.UTF_8))
        .contains("text-embedding-3-small")
        .doesNotContain("text-default-1536");
    assertThat(call.headers()).containsEntry("Authorization", "Bearer sk-test-key");
  }

  @Test
  @DisplayName("the credential is read at call time rather than held")
  void theCredentialIsReadAtCallTimeRatherThanHeld() {
    final ScriptedTransport transport =
        new ScriptedTransport()
            .respondWith(200, body(List.of(flat(DIMENSION, 1f)), 3))
            .respondWith(200, body(List.of(flat(DIMENSION, 1f)), 3));
    final String[] current = {"sk-first"};
    final OpenAiEmbeddingProvider adapter =
        new OpenAiEmbeddingProvider(transport, () -> current[0], CLOCK, 5_000L);

    adapter.embed(request("one"));
    current[0] = "sk-rotated";
    adapter.embed(request("two"));

    // A credential captured at construction survives its own rotation and starts failing at the
    // least convenient moment. Reading it per call is what makes rotation a non-event.
    assertThat(transport.seen.get(0).headers()).containsEntry("Authorization", "Bearer sk-first");
    assertThat(transport.seen.get(1).headers()).containsEntry("Authorization", "Bearer sk-rotated");
  }

  @Test
  @DisplayName("input text is JSON-escaped, control characters included")
  void inputTextIsJsonEscapedControlCharactersIncluded() {
    final ScriptedTransport transport =
        new ScriptedTransport().respondWith(200, body(List.of(flat(DIMENSION, 1f)), 3));
    // Built by concatenation rather than as one literal so the control character is unambiguous: a
    // \\u0001 escape in Java source is processed before lexing, which is a subtlety a reader should
    // not have to hold in their head to know what this test sends.
    final String awkward = "he said \"hi\"\tand\n" + (char) 0x01 + "tail\\done";

    provider(transport).embed(request(awkward));

    final String json = new String(transport.seen.get(0).body(), StandardCharsets.UTF_8);
    // A naive escaper handles the quote and misses the bare control character, which arrives the
    // first time someone embeds a document carrying one. The provider then rejects the whole body
    // as
    // malformed, and it presents as a provider fault rather than as an encoding bug here.
    assertThat(json).contains("\\\"hi");
    assertThat(json).contains("\\t").contains("\\n").contains("\\u0001").contains("\\\\done");
    assertThat(json.indexOf(0x01)).isEqualTo(-1);
  }

  @ParameterizedTest(name = "status {0} maps to {1}")
  @CsvSource({
    "429, RATE_LIMITED",
    "503, UNAVAILABLE",
    "500, UNAVAILABLE",
    "502, UNAVAILABLE",
    "408, TIMEOUT",
    "504, TIMEOUT",
    "401, AUTH_FAILED",
    "403, AUTH_FAILED",
    "400, REJECTED",
    "404, REJECTED"
  })
  @DisplayName("provider statuses map onto neutral failures")
  void providerStatusesMapOntoNeutralFailures(final int status, final EmbeddingFailure expected) {
    final ScriptedTransport transport =
        new ScriptedTransport().respondWith(status, "{\"error\":{}}");

    assertThatThrownBy(() -> provider(transport).embed(request("x")))
        .isInstanceOf(EmbeddingTransportException.class)
        .satisfies(
            thrown ->
                assertThat(((EmbeddingTransportException) thrown).reason()).isEqualTo(expected));
  }

  @ParameterizedTest(name = "status {0} is retryable: {1}")
  @CsvSource({
    "429, true",
    "503, true",
    "408, true",
    "400, false",
    "401, false",
    "403, false",
    "404, false"
  })
  @DisplayName("the mission retry rules hold exactly")
  void theMissionRetryRulesHoldExactly(final int status, final boolean retryable) {
    final ScriptedTransport transport = new ScriptedTransport().respondWith(status, "{}");

    assertThatThrownBy(() -> provider(transport).embed(request("x")))
        .isInstanceOf(EmbeddingTransportException.class)
        .satisfies(
            thrown ->
                assertThat(((EmbeddingTransportException) thrown).reason().retryable())
                    .isEqualTo(retryable));
  }

  @Test
  @DisplayName("a non-200 leaves the adapter degraded rather than healthy")
  void aNon200LeavesTheAdapterDegradedRatherThanHealthy() {
    final ScriptedTransport transport = new ScriptedTransport().respondWith(503, "{}");
    final OpenAiEmbeddingProvider adapter = provider(transport);

    assertThatThrownBy(() -> adapter.embed(request("x")))
        .isInstanceOf(EmbeddingTransportException.class);

    assertThat(adapter.health().state())
        .isNotEqualTo(
            io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth.State.HEALTHY);
    assertThat(adapter.health().lastFailureReason()).contains(EmbeddingFailure.UNAVAILABLE);
  }

  @Test
  @DisplayName("a truncated response body is refused rather than half-parsed")
  void aTruncatedResponseBodyIsRefusedRatherThanHalfParsed() {
    final ScriptedTransport transport =
        new ScriptedTransport().respondWith(200, "{\"data\":[{\"embedding\":[0.1,0.2");

    assertThatThrownBy(() -> provider(transport).embed(request("x")))
        .isInstanceOf(EmbeddingTransportException.class);
  }

  @Test
  @DisplayName("a response with no embeddings is refused")
  void aResponseWithNoEmbeddingsIsRefused() {
    final ScriptedTransport transport = new ScriptedTransport().respondWith(200, "{\"data\":[]}");

    assertThatThrownBy(() -> provider(transport).embed(request("x")))
        .isInstanceOf(EmbeddingTransportException.class);
  }

  @Test
  @DisplayName("a response with a non-numeric component is refused")
  void aResponseWithANonNumericComponentIsRefused() {
    final ScriptedTransport transport =
        new ScriptedTransport().respondWith(200, "{\"data\":[{\"embedding\":[0.1,\"oops\",0.3]}]}");

    assertThatThrownBy(() -> provider(transport).embed(request("x")))
        .isInstanceOf(EmbeddingTransportException.class);
  }

  @Test
  @DisplayName("a response with the wrong number of vectors is refused")
  void aResponseWithTheWrongNumberOfVectorsIsRefused() {
    final ScriptedTransport transport =
        new ScriptedTransport().respondWith(200, body(List.of(flat(DIMENSION, 1f)), 3));

    // Two inputs, one vector back. Pairing them positionally would silently attach a vector to the
    // wrong text — a corruption that no downstream component can detect.
    assertThatThrownBy(() -> provider(transport).embed(request("a", "b")))
        .isInstanceOf(EmbeddingTransportException.class);
  }

  @Test
  @DisplayName("a model the adapter does not serve is refused without a call")
  void aModelTheAdapterDoesNotServeIsRefusedWithoutACall() {
    final ScriptedTransport transport = new ScriptedTransport();

    assertThatThrownBy(
            () ->
                provider(transport)
                    .embed(
                        new EmbeddingRequest(
                            ACME,
                            "unknown",
                            List.of("x"),
                            EmbeddingRequest.EmbeddingPurpose.WRITE)))
        .isInstanceOf(EmbeddingTransportException.class);
    assertThat(transport.seen).isEmpty();
  }

  @Test
  @DisplayName("a transport failure propagates as retryable")
  void aTransportFailurePropagatesAsRetryable() {
    final ScriptedTransport transport = new ScriptedTransport().failWith(EmbeddingFailure.NETWORK);

    // The one condition an adapter throws rather than reports: nothing is known about whether the
    // work happened, which is exactly the case retry must always cover.
    assertThatThrownBy(() -> provider(transport).embed(request("x")))
        .isInstanceOf(EmbeddingTransportException.class)
        .satisfies(
            thrown ->
                assertThat(((EmbeddingTransportException) thrown).reason().retryable()).isTrue());
  }

  @Test
  @DisplayName("the declared capability matches what the adapter actually serves")
  void theDeclaredCapabilityMatchesWhatTheAdapterActuallyServes() {
    final OpenAiEmbeddingProvider adapter = provider(new ScriptedTransport());

    // The batcher splits according to these numbers without ever asking the adapter again, so a
    // capability that overstates the batch limit is a 400 on every large write.
    assertThat(adapter.id().value()).isEqualTo("openai");
    assertThat(adapter.capability().model(MODEL).orElseThrow().dimension()).isEqualTo(1536);
    assertThat(adapter.capability().model("text-large-3072").orElseThrow().dimension())
        .isEqualTo(3072);
    assertThat(adapter.capability().maxBatchSize()).isEqualTo(2048);
    assertThat(adapter.capability().returnsNormalized()).isTrue();
  }

  @Test
  @DisplayName("prices are micros per million tokens, not dollars per million tokens")
  void pricesAreMicrosPerMillionTokensNotDollarsPerMillionTokens() {
    final OpenAiEmbeddingProvider adapter = provider(new ScriptedTransport());

    // A micro is a millionth of a currency unit, so $0.02 per million tokens is 20_000 and $0.13 is
    // 130_000. Off by a thousand here is not a rounding error: it is a budget check that admits a
    // thousand times the spend the operator authorised, silently, on the cheapest-looking path in
    // the system.
    assertThat(adapter.capability().model(MODEL).orElseThrow().costPerMillionInputTokensMicros())
        .isEqualTo(20_000L);
    assertThat(
            adapter
                .capability()
                .model("text-large-3072")
                .orElseThrow()
                .costPerMillionInputTokensMicros())
        .isEqualTo(130_000L);
  }

  /**
   * A response body whose elements carry explicit indexes in a chosen document order.
   *
   * @param declaredIndexes the index each element declares, in document order
   * @param marks a recognisable first component for each element, in document order
   * @return the JSON body
   */
  private static String indexedBody(final int[] declaredIndexes, final float[] marks) {
    final StringBuilder json = new StringBuilder("{\"data\":[");
    for (int i = 0; i < declaredIndexes.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"object\":\"embedding\",\"index\":")
          .append(declaredIndexes[i])
          .append(",\"embedding\":[");
      for (int c = 0; c < DIMENSION; c++) {
        if (c > 0) {
          json.append(',');
        }
        json.append(c == 0 ? marks[i] : 0.5f);
      }
      json.append("]}");
    }
    return json.append("],\"usage\":{\"prompt_tokens\":4}}").toString();
  }

  @Test
  @DisplayName("vectors are paired by the declared index, not by document order")
  void vectorsArePairedByTheDeclaredIndexNotByDocumentOrder() {
    // The API carries an explicit index per element precisely because array order is not the
    // contract. Pairing by position attaches a perfectly valid vector to the wrong text: the count
    // is right, the dimensions are right, every value is finite, and the corpus is silently wrong
    // for ever. No downstream component can detect it.
    final ScriptedTransport transport =
        new ScriptedTransport()
            .respondWith(200, indexedBody(new int[] {1, 0}, new float[] {0.9f, 0.1f}));

    final EmbeddingResponse response = provider(transport).embed(request("FIRST", "SECOND"));

    assertThat(response.at(0).orElseThrow().vector()[0]).isEqualTo(0.1f);
    assertThat(response.at(1).orElseThrow().vector()[0]).isEqualTo(0.9f);
  }

  @Test
  @DisplayName("document order is honoured when no index is declared")
  void documentOrderIsHonouredWhenNoIndexIsDeclared() {
    final ScriptedTransport transport =
        new ScriptedTransport()
            .respondWith(200, body(List.of(flat(DIMENSION, 0.1f), flat(DIMENSION, 0.9f)), 4));

    final EmbeddingResponse response = provider(transport).embed(request("FIRST", "SECOND"));

    assertThat(response.at(0).orElseThrow().vector()[0]).isEqualTo(0.1f);
    assertThat(response.at(1).orElseThrow().vector()[0]).isEqualTo(0.9f);
  }

  @Test
  @DisplayName("indexes that are not a permutation are refused rather than guessed at")
  void indexesThatAreNotAPermutationAreRefusedRatherThanGuessedAt() {
    final ScriptedTransport duplicate =
        new ScriptedTransport()
            .respondWith(200, indexedBody(new int[] {0, 0}, new float[] {0.9f, 0.1f}));
    assertThatThrownBy(() -> provider(duplicate).embed(request("A", "B")))
        .isInstanceOf(EmbeddingTransportException.class);

    final ScriptedTransport outOfRange =
        new ScriptedTransport()
            .respondWith(200, indexedBody(new int[] {0, 7}, new float[] {0.9f, 0.1f}));
    assertThatThrownBy(() -> provider(outOfRange).embed(request("A", "B")))
        .isInstanceOf(EmbeddingTransportException.class);
  }

  @Test
  @DisplayName("an absurd token count cannot be recorded as a trivial cost")
  void anAbsurdTokenCountCannotBeRecordedAsATrivialCost() {
    // prompt_tokens arrives over the network and is multiplied by a price into metering and audit.
    // Before saturation, 461_168_601_842_739 reported ONE MICRO for what should be ~$9.2M.
    final ScriptedTransport transport =
        new ScriptedTransport()
            .respondWith(
                200,
                "{\"data\":[{\"embedding\":["
                    + vectorCsv()
                    + "]}],"
                    + "\"usage\":{\"prompt_tokens\":461168601842739}}");

    final EmbeddingResponse response = provider(transport).embed(request("x"));

    assertThat(response.usage().costMicros()).isGreaterThan(9_000_000_000_000L);
  }

  @Test
  @DisplayName("an over-long token count saturates instead of throwing a raw parse error")
  void anOverLongTokenCountSaturatesInsteadOfThrowingARawParseError() {
    // Long.parseLong on a 20-digit run throws NumberFormatException, which is NOT an
    // EmbeddingTransportException, so it escaped the pipeline's retry handling entirely.
    final ScriptedTransport transport =
        new ScriptedTransport()
            .respondWith(
                200,
                "{\"data\":[{\"embedding\":["
                    + vectorCsv()
                    + "]}],"
                    + "\"usage\":{\"prompt_tokens\":99999999999999999999}}");

    final EmbeddingResponse response = provider(transport).embed(request("x"));

    assertThat(response.usage().inputTokens()).isEqualTo(Long.MAX_VALUE);
    assertThat(response.usage().costMicros()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  @DisplayName("a negative token count is refused rather than read as zero")
  void aNegativeTokenCountIsRefusedRatherThanReadAsZero() {
    // The digit scan used to stop at the sign, so "-5" read as zero: a call that happened, charged
    // as nothing.
    final ScriptedTransport transport =
        new ScriptedTransport()
            .respondWith(
                200,
                "{\"data\":[{\"embedding\":["
                    + vectorCsv()
                    + "]}],"
                    + "\"usage\":{\"prompt_tokens\":-5}}");

    assertThatThrownBy(() -> provider(transport).embed(request("x")))
        .isInstanceOf(EmbeddingTransportException.class);
  }

  /**
   * A comma-separated vector of the model's width.
   *
   * @return the component list
   */
  private static String vectorCsv() {
    final StringBuilder csv = new StringBuilder();
    for (int i = 0; i < DIMENSION; i++) {
      if (i > 0) {
        csv.append(',');
      }
      csv.append("0.1");
    }
    return csv.toString();
  }
}
