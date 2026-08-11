package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.DIMENSION;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingCapability;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The canonical shapes: what they refuse to be constructed as, and what they refuse to leak.
 *
 * <p>Two themes run through this file. The first is that a malformed embedding must be impossible
 * to <em>hold</em>, not merely impossible to produce — validation in the constructor rather than in
 * the pipeline, so no future caller can route around it. The second is that none of these types may
 * print tenant content: an embedding request carries exactly the text the PII engine has just
 * classified as sensitive, and a record's generated {@code toString} would put it in the first log
 * line that touches it.
 */
@DisplayName("canonical embedding contracts")
final class EmbeddingContractTest {

  private static CanonicalEmbedding embedding(final int dimension, final float[] vector) {
    return new CanonicalEmbedding(
        ProviderId.of("p"),
        MODEL,
        dimension,
        Instant.EPOCH,
        EmbeddingUsage.FREE,
        true,
        Map.of("k", "v"),
        vector);
  }

  @Nested
  @DisplayName("CanonicalEmbedding")
  final class Canonical {

    @Test
    @DisplayName("refuses a vector whose length disagrees with its declared dimension")
    void refusesAVectorWhoseLengthDisagreesWithItsDeclaredDimension() {
      // The failure that corrupts an index silently rather than loudly, refused at construction so
      // that no code path anywhere can produce one.
      assertThatThrownBy(() -> embedding(8, EmbeddingFixtures.flat(16, 1.0f)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("8")
          .hasMessageContaining("16");
    }

    @Test
    @DisplayName("refuses a non-positive dimension")
    void refusesANonPositiveDimension() {
      assertThatThrownBy(() -> embedding(0, new float[0]))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses missing provenance")
    void refusesMissingProvenance() {
      final float[] vector = EmbeddingFixtures.flat(DIMENSION, 1.0f);

      assertThatThrownBy(
              () ->
                  new CanonicalEmbedding(
                      null,
                      MODEL,
                      DIMENSION,
                      Instant.EPOCH,
                      EmbeddingUsage.FREE,
                      true,
                      Map.of(),
                      vector))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(
              () ->
                  new CanonicalEmbedding(
                      ProviderId.of("p"),
                      " ",
                      DIMENSION,
                      Instant.EPOCH,
                      EmbeddingUsage.FREE,
                      true,
                      Map.of(),
                      vector))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("copies the vector in, so the producer cannot alter it afterwards")
    void copiesTheVectorInSoTheProducerCannotAlterItAfterwards() {
      final float[] mutable = EmbeddingFixtures.flat(DIMENSION, 1.0f);
      final CanonicalEmbedding held = embedding(DIMENSION, mutable);

      mutable[0] = 99.0f;

      assertThat(held.vector()[0]).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("copies the vector out, so a consumer cannot alter what a cache holds")
    void copiesTheVectorOutSoAConsumerCannotAlterWhatACacheHolds() {
      final CanonicalEmbedding held = embedding(DIMENSION, EmbeddingFixtures.flat(DIMENSION, 1.0f));

      held.vector()[0] = 99.0f;

      assertThat(held.vector()[0]).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("freezes its metadata")
    void freezesItsMetadata() {
      final Map<String, String> mutable = new HashMap<>(Map.of("k", "v"));
      final CanonicalEmbedding held =
          new CanonicalEmbedding(
              ProviderId.of("p"),
              MODEL,
              DIMENSION,
              Instant.EPOCH,
              EmbeddingUsage.FREE,
              true,
              mutable,
              EmbeddingFixtures.flat(DIMENSION, 1.0f));

      mutable.put("k2", "v2");

      assertThat(held.metadata()).hasSize(1);
      assertThatThrownBy(() -> held.metadata().put("k3", "v3"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("prints its provenance and not its components")
    void printsItsProvenanceAndNotItsComponents() {
      // Three thousand floats in a log line are unreadable and are a copy of derived tenant content
      // in a place nobody audited.
      final String rendered =
          embedding(DIMENSION, EmbeddingFixtures.flat(DIMENSION, 0.125f)).toString();

      assertThat(rendered)
          .contains("provider=p")
          .contains("model=" + MODEL)
          .contains("dimension=" + DIMENSION)
          .doesNotContain("0.125");
    }
  }

  @Nested
  @DisplayName("EmbeddingRequest")
  final class Request {

    @Test
    @DisplayName("refuses an empty batch")
    void refusesAnEmptyBatch() {
      assertThatThrownBy(
              () ->
                  new EmbeddingRequest(
                      ACME, MODEL, List.of(), EmbeddingRequest.EmbeddingPurpose.WRITE))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses a null text inside an otherwise valid batch")
    void refusesANullTextInsideAnOtherwiseValidBatch() {
      final List<String> withNull = new ArrayList<>();
      withNull.add("fine");
      withNull.add(null);

      // List.copyOf would already throw, but relying on that leaves the guarantee owned by a JDK
      // implementation detail rather than by this record.
      assertThatThrownBy(
              () ->
                  new EmbeddingRequest(
                      ACME, MODEL, withNull, EmbeddingRequest.EmbeddingPurpose.WRITE))
          .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("a single-text request is a batch of one")
    void aSingleTextRequestIsABatchOfOne() {
      final EmbeddingRequest request =
          EmbeddingRequest.of(ACME, MODEL, "solo", EmbeddingRequest.EmbeddingPurpose.QUERY);

      assertThat(request.size()).isEqualTo(1);
      assertThat(request.texts()).containsExactly("solo");
    }

    @Test
    @DisplayName("freezes its texts")
    void freezesItsTexts() {
      final EmbeddingRequest request =
          new EmbeddingRequest(ACME, MODEL, List.of("a"), EmbeddingRequest.EmbeddingPurpose.WRITE);

      assertThatThrownBy(() -> request.texts().add("b"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("prints its shape and never its inputs")
    void printsItsShapeAndNeverItsInputs() {
      final String rendered =
          new EmbeddingRequest(
                  ACME,
                  MODEL,
                  List.of("patient name is Jane Doe", "ssn 123-45-6789"),
                  EmbeddingRequest.EmbeddingPurpose.WRITE)
              .toString();

      assertThat(rendered)
          .doesNotContain("Jane Doe")
          .doesNotContain("123-45-6789")
          .contains("inputs=2")
          .contains("acme");
    }

    @ParameterizedTest
    @EnumSource(EmbeddingRequest.EmbeddingPurpose.class)
    @DisplayName("every purpose is constructible")
    void everyPurposeIsConstructible(final EmbeddingRequest.EmbeddingPurpose purpose) {
      assertThat(EmbeddingRequest.of(ACME, MODEL, "x", purpose).purpose()).isEqualTo(purpose);
    }
  }

  @Nested
  @DisplayName("EmbeddingResponse")
  final class Response {

    private final CanonicalEmbedding vector =
        embedding(DIMENSION, EmbeddingFixtures.flat(DIMENSION, 1.0f));

    @Test
    @DisplayName("refuses an empty outcome list")
    void refusesAnEmptyOutcomeList() {
      assertThatThrownBy(() -> new EmbeddingResponse(List.of(), EmbeddingUsage.FREE))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reports partial success as partial rather than as failure")
    void reportsPartialSuccessAsPartialRatherThanAsFailure() {
      final EmbeddingResponse response =
          new EmbeddingResponse(
              List.of(
                  new EmbeddingResponse.Outcome.Embedded(vector),
                  new EmbeddingResponse.Outcome.Failed(EmbeddingFailure.TOO_LARGE, "too long"),
                  new EmbeddingResponse.Outcome.Embedded(vector)),
              EmbeddingUsage.FREE);

      // A batch of sixty-four in which one input is too long must return sixty-three embeddings.
      // All-or-nothing would let one oversized record block every write batched alongside it.
      assertThat(response.complete()).isFalse();
      assertThat(response.embedded()).isEqualTo(2);
      assertThat(response.failures()).hasSize(1);
      assertThat(response.at(0)).isPresent();
      assertThat(response.at(1)).isEmpty();
      assertThat(response.at(2)).isPresent();
    }

    @Test
    @DisplayName("an all-success response is complete")
    void anAllSuccessResponseIsComplete() {
      final EmbeddingResponse response =
          new EmbeddingResponse(
              List.of(new EmbeddingResponse.Outcome.Embedded(vector)), EmbeddingUsage.FREE);

      assertThat(response.complete()).isTrue();
      assertThat(response.failures()).isEmpty();
    }
  }

  @Nested
  @DisplayName("EmbeddingUsage")
  final class Usage {

    @Test
    @DisplayName("adds componentwise")
    void addsComponentwise() {
      assertThat(new EmbeddingUsage(10L, 1, 5L).plus(new EmbeddingUsage(3L, 2, 7L)))
          .isEqualTo(new EmbeddingUsage(13L, 3, 12L));
    }

    @Test
    @DisplayName("a free usage is the identity")
    void aFreeUsageIsTheIdentity() {
      final EmbeddingUsage usage = new EmbeddingUsage(10L, 1, 5L);

      assertThat(usage.plus(EmbeddingUsage.FREE)).isEqualTo(usage);
      assertThat(EmbeddingUsage.FREE.plus(usage)).isEqualTo(usage);
    }
  }

  @Nested
  @DisplayName("EmbeddingCapability and EmbeddingModel")
  final class Capability {

    @Test
    @DisplayName("a provider must serve at least one model")
    void aProviderMustServeAtLeastOneModel() {
      assertThatThrownBy(() -> new EmbeddingCapability(Set.of(), 10, 10, true, true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refuses non-positive limits")
    void refusesNonPositiveLimits() {
      final Set<EmbeddingModel> models = Set.of(new EmbeddingModel("m", 8, 100, 1L));

      assertThatThrownBy(() -> new EmbeddingCapability(models, 0, 10, true, true))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new EmbeddingCapability(models, 10, 0, true, true))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("finds a served model by neutral id and nothing else")
    void findsAServedModelByNeutralIdAndNothingElse() {
      final EmbeddingCapability capability =
          new EmbeddingCapability(
              Set.of(
                  new EmbeddingModel("small", 8, 100, 1L),
                  new EmbeddingModel("large", 16, 100, 2L)),
              10,
              10,
              true,
              true);

      assertThat(capability.model("small")).isPresent();
      assertThat(capability.model("large").orElseThrow().dimension()).isEqualTo(16);
      assertThat(capability.model("SMALL")).isEmpty();
      assertThat(capability.model("unknown")).isEmpty();
    }

    @Test
    @DisplayName("a model refuses a non-positive dimension, limit or negative price")
    void aModelRefusesANonPositiveDimensionLimitOrNegativePrice() {
      assertThatThrownBy(() -> new EmbeddingModel("m", 0, 100, 1L))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new EmbeddingModel("m", 8, 0, 1L))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new EmbeddingModel("m", 8, 100, -1L))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new EmbeddingModel(" ", 8, 100, 1L))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("EmbeddingFailure")
  final class Failure {

    @ParameterizedTest
    @EnumSource(EmbeddingFailure.class)
    @DisplayName("every failure kind states whether it is retryable")
    void everyFailureKindStatesWhetherItIsRetryable(final EmbeddingFailure reason) {
      final Set<EmbeddingFailure> transient_ =
          Set.of(
              EmbeddingFailure.RATE_LIMITED,
              EmbeddingFailure.TIMEOUT,
              EmbeddingFailure.UNAVAILABLE,
              EmbeddingFailure.NETWORK);

      // The retry classification is data on the enum rather than a condition each adapter writes,
      // so it is asserted here once for every kind and cannot drift per provider.
      assertThat(reason.retryable()).isEqualTo(transient_.contains(reason));
    }
  }

  @Nested
  @DisplayName("transport values")
  class TransportValues {

    @Test
    @DisplayName("a call cannot be altered after it has been handed to the transport")
    void aCallCannotBeAlteredAfterItHasBeenHandedToTheTransport() {
      // Call and Exchange were plain records over a byte[] and a Map with no copying at either
      // end, so the caller and the transport shared one body array. A retry that re-sent a Call
      // whose bytes something had since edited would sign and send a request nobody wrote.
      final byte[] body = {1, 2, 3};
      final Map<String, String> headers = new HashMap<>(Map.of("authorization", "Bearer real"));
      final EmbeddingTransportPort.Call call =
          new EmbeddingTransportPort.Call("POST", "https://x/y", headers, body, 1_000L);

      body[0] = 9;
      headers.put("authorization", "Bearer swapped");

      assertThat(call.body()).containsExactly(1, 2, 3);
      assertThat(call.headers()).containsExactly(Map.entry("authorization", "Bearer real"));
      assertThatThrownBy(() -> call.headers().put("x", "y"))
          .isInstanceOf(UnsupportedOperationException.class);

      // And the array handed back is a copy, so a reader cannot edit the call either.
      call.body()[1] = 9;
      assertThat(call.body()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("a response body cannot be altered after the transport returned it")
    void aResponseBodyCannotBeAlteredAfterTheTransportReturnedIt() {
      final byte[] body = {7, 8};
      final EmbeddingTransportPort.Exchange exchange =
          new EmbeddingTransportPort.Exchange(200, body);

      body[0] = 9;
      exchange.body()[1] = 9;

      assertThat(exchange.body()).containsExactly(7, 8);
    }

    @Test
    @DisplayName("a null header value survives the copy instead of throwing")
    void aNullHeaderValueSurvivesTheCopyInsteadOfThrowing() {
      // A null header value means "absent"; the transport skips it rather than sending it. Freezing
      // the map with Map.copyOf would have turned that supported input into a NullPointerException
      // at construction, which is how the first attempt at this broke
      // HttpEmbeddingTransportTest.aNullOrAbsentHeaderValueIsSkipped.
      final Map<String, String> withNull = new HashMap<>();
      withNull.put("X-Present", "yes");
      withNull.put("X-Absent", null);

      final EmbeddingTransportPort.Call call =
          new EmbeddingTransportPort.Call("POST", "https://x", withNull, new byte[0], 1L);

      assertThat(call.headers()).containsEntry("X-Present", "yes").containsKey("X-Absent");
      assertThat(call.headers().get("X-Absent")).isNull();
    }

    @Test
    @DisplayName("absent headers and bodies are empty rather than null")
    void absentHeadersAndBodiesAreEmptyRatherThanNull() {
      final EmbeddingTransportPort.Call call =
          new EmbeddingTransportPort.Call("GET", "https://x", null, null, 1L);

      assertThat(call.headers()).isEmpty();
      assertThat(call.body()).isEmpty();
      assertThat(new EmbeddingTransportPort.Exchange(204, null).body()).isEmpty();
    }
  }
}
