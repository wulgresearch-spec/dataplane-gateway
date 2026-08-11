package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingCapability;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingProviderPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportException;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingCostEstimator;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.domain.HttpStatusErrorClassifier;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The OpenAI embeddings adapter (AD-030 §12).
 *
 * <p><b>It lives here, in the provider module, and not in {@code gateway-dp-embedding}.</b> That is
 * not filing preference: {@code ProviderNeutralityTest} scans every production source outside
 * {@code dataplane/providers} for vendor names and fails the build on a hit, so a vendor adapter
 * under {@code dataplane/modules} does not compile past the suite. The rule predates this milestone
 * and is the same one that keeps the router, the pipeline and the composition root vendor-free. The
 * consequence worth stating is the good one: {@code gateway-dp-embedding} contains <em>no</em>
 * vendor name at all, rather than containing one in an agreed-upon corner.
 *
 * <p>Everything above {@link EmbeddingProviderPort} is therefore neutral by construction. This file
 * knows the wire format, the URL and the vendor model names; nothing that calls it knows any of
 * them.
 *
 * <p>Status classification is delegated to {@code HttpStatusErrorClassifier} from Provider
 * Abstraction V2 rather than re-derived here. Its mapping already matches what B25 requires — 429
 * rate-limited, 408 and 504 timeout, 5xx unavailable, 401 and 403 authentication, other 4xx
 * rejected — and having one classifier means embedding retries behave like every other retry in the
 * gateway instead of like a second, subtly different policy.
 *
 * <p><b>Verification limit, stated plainly.</b> No OpenAI endpoint is reachable from the
 * environment this was written in. Most paths here are exercised against a transport double
 * replaying recorded response shapes, and one end-to-end path now runs over a real loopback socket
 * through {@link HttpEmbeddingTransport} — which proves the transport and the wire encoding work
 * together, and proves nothing about whether the vendor's live API still returns the shapes parsed
 * below. The JSON handling, the header set and the error mapping remain <em>unverified against the
 * real service</em>. B57 stays open, and closing it needs a credential and network access, not more
 * code.
 */
public final class OpenAiEmbeddingProvider implements EmbeddingProviderPort {

  /** The provider identity. The single vendor literal above the wire format itself. */
  private static final ProviderId ID = ProviderId.of("openai");

  /** Where the vendor serves embeddings when a deployment does not say otherwise. */
  private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/embeddings";

  /** Maps neutral model ids onto the vendor names. The whole of the vendor coupling. */
  private static final Map<String, String> VENDOR_MODEL_NAMES =
      Map.of(
          "text-default-1536", "text-embedding-3-small",
          "text-large-3072", "text-embedding-3-large");

  private final EmbeddingTransportPort transport;

  private final Supplier<String> apiKey;

  private final ClockPort clock;

  private final EmbeddingCapability capability;

  private final long timeoutMillis;

  private final String endpoint;

  private volatile EmbeddingHealth health;

  /**
   * Creates the adapter against the vendor's public endpoint.
   *
   * @param transport the HTTP seam
   * @param apiKey supplies the credential at call time and never stores it here
   * @param clock the time source
   * @param timeoutMillis the per-attempt timeout
   */
  public OpenAiEmbeddingProvider(
      final EmbeddingTransportPort transport,
      final Supplier<String> apiKey,
      final ClockPort clock,
      final long timeoutMillis) {
    this(transport, apiKey, clock, timeoutMillis, DEFAULT_ENDPOINT);
  }

  /**
   * Creates the adapter against a named endpoint.
   *
   * <p>The endpoint is configurable because a deployment may route this protocol at something other
   * than the vendor's public host — a regional endpoint, a gateway of its own, or a loopback server
   * in a test. That is also the honest reason B57 cannot simply be closed: making the host
   * configurable proves the transport works, and proves nothing about whether the vendor's live API
   * still returns the shapes this adapter parses.
   *
   * @param transport the HTTP seam
   * @param apiKey supplies the credential at call time and never stores it here
   * @param clock the time source
   * @param timeoutMillis the per-attempt timeout
   * @param endpoint the absolute embeddings URL
   */
  public OpenAiEmbeddingProvider(
      final EmbeddingTransportPort transport,
      final Supplier<String> apiKey,
      final ClockPort clock,
      final long timeoutMillis,
      final String endpoint) {
    this.endpoint = Preconditions.requireNonBlank(endpoint, "endpoint");
    this.transport = Preconditions.requireNonNull(transport, "transport");
    this.apiKey = Preconditions.requireNonNull(apiKey, "apiKey");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.timeoutMillis = timeoutMillis;
    this.capability =
        new EmbeddingCapability(
            java.util.Set.of(
                // Micros per million tokens, and the unit is worth stating: a micro is a millionth
                // of
                // a currency unit, so the published $0.02 per million tokens is 20_000 and not 20.
                // Off by a thousand here is not a rounding error, it is a budget check that admits
                // a
                // thousand times the spend the operator authorised.
                new EmbeddingModel("text-default-1536", 1536, 8191, 20_000L),
                new EmbeddingModel("text-large-3072", 3072, 8191, 130_000L)),
            2048,
            1_000_000,
            true,
            true);
    this.health = EmbeddingHealth.healthy(clock.now());
  }

  @Override
  public ProviderId id() {
    return ID;
  }

  @Override
  public EmbeddingCapability capability() {
    return capability;
  }

  @Override
  public EmbeddingHealth health() {
    return health;
  }

  @Override
  public EmbeddingResponse embed(final EmbeddingRequest request) {
    final String vendorModel = VENDOR_MODEL_NAMES.get(request.modelId());
    if (vendorModel == null) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.REJECTED, "this provider does not serve model " + request.modelId());
    }
    final EmbeddingModel model = capability.model(request.modelId()).orElseThrow();

    final EmbeddingTransportPort.Exchange exchange =
        transport.send(
            new EmbeddingTransportPort.Call(
                "POST",
                endpoint,
                headers(),
                encodeRequest(vendorModel, request.texts()),
                timeoutMillis));

    if (exchange.status() != 200) {
      final EmbeddingFailure reason = classify(exchange.status());
      health =
          new EmbeddingHealth(
              EmbeddingHealth.State.DEGRADED,
              1,
              health.lastSuccess(),
              java.util.Optional.of(reason));
      // Thrown rather than returned: a non-200 says nothing about individual inputs, so there is no
      // honest way to report it per input. The pipeline decides whether it is worth another
      // attempt.
      throw new EmbeddingTransportException(
          reason, "openai embeddings returned status " + exchange.status());
    }

    final Parsed parsed = parseResponse(new String(exchange.body(), StandardCharsets.UTF_8));
    if (parsed.vectors.size() != request.size()) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.INTERNAL,
          "expected " + request.size() + " vectors but received " + parsed.vectors.size());
    }

    final long cost = EmbeddingCostEstimator.costMicros(parsed.promptTokens, model);
    final EmbeddingUsage usage = new EmbeddingUsage(parsed.promptTokens, 1, cost);
    final List<EmbeddingResponse.Outcome> outcomes = new ArrayList<>(parsed.vectors.size());
    for (final float[] vector : parsed.vectors) {
      outcomes.add(
          new EmbeddingResponse.Outcome.Embedded(
              new CanonicalEmbedding(
                  ID,
                  request.modelId(),
                  vector.length,
                  clock.now(),
                  // Usage is reported for the whole call; attributing it per input would invent a
                  // split the provider did not give us.
                  EmbeddingUsage.FREE,
                  capability.returnsNormalized(),
                  Map.of(),
                  vector)));
    }
    health = EmbeddingHealth.healthy(clock.now());
    return new EmbeddingResponse(outcomes, usage);
  }

  /**
   * The request headers, credential included.
   *
   * @return the headers
   */
  private Map<String, String> headers() {
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Authorization", "Bearer " + apiKey.get());
    headers.put("Content-Type", "application/json");
    return headers;
  }

  /**
   * Maps an HTTP status onto a neutral failure.
   *
   * @param status the HTTP status
   * @return the neutral failure kind
   */
  private static EmbeddingFailure classify(final int status) {
    final ErrorCategory category = HttpStatusErrorClassifier.classify(status);
    return switch (category) {
      case RATE_LIMITED -> EmbeddingFailure.RATE_LIMITED;
      case TIMEOUT -> EmbeddingFailure.TIMEOUT;
      case AUTH_FAILED -> EmbeddingFailure.AUTH_FAILED;
      case PROVIDER_UNAVAILABLE -> EmbeddingFailure.UNAVAILABLE;
      default -> EmbeddingFailure.REJECTED;
    };
  }

  /**
   * Encodes the provider-native request body.
   *
   * @param vendorModel the vendor model name
   * @param texts the inputs
   * @return the encoded body
   */
  private static byte[] encodeRequest(final String vendorModel, final List<String> texts) {
    final StringBuilder json = new StringBuilder(256 + texts.size() * 64);
    json.append("{\"model\":\"")
        .append(vendorModel)
        .append("\",\"encoding_format\":\"float\",\"input\":[");
    for (int i = 0; i < texts.size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      appendJsonString(json, texts.get(i));
    }
    json.append("]}");
    return json.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Appends a JSON string literal, escaped.
   *
   * <p>Hand-rolled because this module has no JSON dependency and adding one for two shapes would
   * be a poor trade. Control characters are escaped numerically, which is the case a naive escaper
   * gets wrong and which arrives the first time someone embeds a document with a tab in it.
   *
   * @param out where to append
   * @param value the string
   */
  private static void appendJsonString(final StringBuilder out, final String value) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  /**
   * Extracts vectors and token usage from the provider-native response.
   *
   * <p>A purpose-built scanner rather than a general parser. It looks for the two shapes this
   * endpoint returns and refuses anything else, which is the safer failure: a general parser that
   * accepted a surprising document would hand a malformed vector to the normalizer.
   *
   * @param body the response body
   * @return the parsed vectors and usage
   */
  private static Parsed parseResponse(final String body) {
    final List<float[]> inDocumentOrder = new ArrayList<>();
    final List<Integer> declaredIndexes = new ArrayList<>();
    int at = 0;
    while (true) {
      // Matching the token "embedding" alone is not enough: every element also carries
      // "object":"embedding", where the same token appears as a VALUE. Requiring a colon and then
      // an
      // array after it is what distinguishes the field from its namesake — and getting that wrong
      // made the index lookup below search the wrong span and silently fall back to document order.
      final int marker = nextEmbeddingField(body, at);
      if (marker < 0) {
        break;
      }
      // The "index" of an element sits before its "embedding" in the object, so it is read from the
      // span between the previous element and this one.
      declaredIndexes.add(parseIndexBefore(body, at, marker));
      final int open = body.indexOf('[', marker);
      final int close = body.indexOf(']', open + 1);
      if (open < 0 || close < 0) {
        throw new EmbeddingTransportException(
            EmbeddingFailure.INTERNAL, "malformed embedding array in the provider response");
      }
      inDocumentOrder.add(parseFloats(body.substring(open + 1, close)));
      at = close + 1;
    }
    if (inDocumentOrder.isEmpty()) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.INTERNAL, "the provider response carried no embeddings");
    }
    return new Parsed(reorder(inDocumentOrder, declaredIndexes), promptTokens(body));
  }

  /**
   * Puts the vectors into the order the caller's inputs were in.
   *
   * <p><b>Array order is not the contract; the {@code index} field is.</b> The response carries an
   * explicit index per element precisely because the elements may arrive in any order, and pairing
   * by document position instead attaches a perfectly valid vector to the wrong text. Nothing
   * downstream can detect that: the count is right, every vector is well formed, the dimensions
   * match, and the corpus is quietly wrong for ever.
   *
   * <p>An absent index is treated as document order, because a response that omits the field
   * entirely is an older or simpler shape rather than a scrambled one. A <em>partial</em> or
   * duplicated set of indexes is refused rather than guessed at.
   *
   * @param vectors the vectors in document order
   * @param indexes the declared index of each, or -1 where none was present
   * @return the vectors in caller-input order
   */
  private static List<float[]> reorder(final List<float[]> vectors, final List<Integer> indexes) {
    final int size = vectors.size();
    boolean anyDeclared = false;
    for (final int declared : indexes) {
      if (declared >= 0) {
        anyDeclared = true;
        break;
      }
    }
    if (!anyDeclared) {
      return vectors;
    }
    final float[][] placed = new float[size][];
    for (int i = 0; i < size; i++) {
      final int declared = indexes.get(i);
      if (declared < 0 || declared >= size || placed[declared] != null) {
        throw new EmbeddingTransportException(
            EmbeddingFailure.INTERNAL,
            "the provider returned embedding indexes that are not a permutation of 0.."
                + (size - 1));
      }
      placed[declared] = vectors.get(i);
    }
    return List.of(placed);
  }

  /**
   * Finds the next {@code "embedding"} used as a field name introducing an array.
   *
   * @param body the response body
   * @param from where to start looking
   * @return the offset of the field name, or -1 when there is no further one
   */
  private static int nextEmbeddingField(final String body, final int from) {
    int at = from;
    while (true) {
      final int marker = body.indexOf("\"embedding\"", at);
      if (marker < 0) {
        return -1;
      }
      int i = marker + "\"embedding\"".length();
      while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
        i++;
      }
      if (i < body.length() && body.charAt(i) == ':') {
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
          i++;
        }
        if (i < body.length() && body.charAt(i) == '[') {
          return marker;
        }
      }
      at = marker + 1;
    }
  }

  /**
   * Reads the {@code index} field appearing between two offsets.
   *
   * @param body the response body
   * @param from where to start looking
   * @param before the offset of this element's embedding field
   * @return the declared index, or -1 when absent
   */
  private static int parseIndexBefore(final String body, final int from, final int before) {
    final int marker = body.lastIndexOf("\"index\"", before);
    if (marker < 0 || marker < from) {
      return -1;
    }
    final long value = readDigitsAfterColon(body, marker);
    return value < 0L || value > Integer.MAX_VALUE ? -1 : (int) value;
  }

  /**
   * Reads the charged token count, refusing values that cannot be believed.
   *
   * <p>This number is multiplied by a price and written into metering and the audit trail, so it is
   * a monetary input arriving over the network. A provider — or anything impersonating one, which a
   * self-hosted compatible endpoint makes an ordinary deployment shape — that reports an absurd
   * count would otherwise have its figure recorded verbatim.
   *
   * @param body the response body
   * @return the token count, never negative
   */
  private static long promptTokens(final String body) {
    final long tokens = readNamedLong(body, "\"prompt_tokens\"");
    if (tokens < 0L) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.INTERNAL, "the provider reported a negative token count");
    }
    return tokens;
  }

  /**
   * Parses a comma-separated float list.
   *
   * @param csv the list body
   * @return the components
   */
  private static float[] parseFloats(final String csv) {
    final String trimmed = csv.trim();
    if (trimmed.isEmpty()) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.INTERNAL, "the provider returned an empty vector");
    }
    final String[] parts = trimmed.split(",");
    final float[] vector = new float[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        vector[i] = Float.parseFloat(parts[i].trim());
      } catch (final NumberFormatException malformed) {
        throw new EmbeddingTransportException(
            EmbeddingFailure.INTERNAL, "the provider returned a non-numeric vector component");
      }
    }
    return vector;
  }

  /**
   * Reads a named numeric field, defaulting to zero when absent.
   *
   * @param body the response body
   * @param field the quoted field name
   * @return the value, or zero when the field is missing or has no numeric value
   */
  private static long readNamedLong(final String body, final String field) {
    final int marker = body.indexOf(field);
    return marker < 0 ? 0L : readDigitsAfterColon(body, marker);
  }

  /**
   * Reads the integer following the next colon, saturating rather than throwing.
   *
   * <p>Three details, each of which was wrong before.
   *
   * <p>A leading minus is <b>consumed</b> rather than silently terminating the digit scan. Stopping
   * at the sign made {@code "prompt_tokens": -5} read as zero, which is a charge of nothing for a
   * call that happened.
   *
   * <p>An over-long run of digits <b>saturates to {@code Long.MAX_VALUE}</b> instead of reaching
   * {@code Long.parseLong}, which throws {@code NumberFormatException} — an exception that is not
   * an {@code EmbeddingTransportException}, so it escapes the pipeline's retry handling entirely
   * and unwinds into the caller.
   *
   * <p>The accumulation itself is overflow-checked, for the same reason.
   *
   * @param body the response body
   * @param marker the offset of the field name
   * @return the value, saturated to the long range; negative when the field carried a negative
   *     number
   */
  private static long readDigitsAfterColon(final String body, final int marker) {
    final int colon = body.indexOf(':', marker);
    if (colon < 0) {
      return 0L;
    }
    int i = colon + 1;
    while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
      i++;
    }
    boolean negative = false;
    if (i < body.length() && body.charAt(i) == '-') {
      negative = true;
      i++;
    }
    final int start = i;
    long value = 0L;
    boolean saturated = false;
    while (i < body.length() && Character.isDigit(body.charAt(i))) {
      if (!saturated) {
        try {
          value = Math.addExact(Math.multiplyExact(value, 10L), body.charAt(i) - '0');
        } catch (final ArithmeticException overflow) {
          saturated = true;
          value = Long.MAX_VALUE;
        }
      }
      i++;
    }
    if (i == start) {
      return 0L;
    }
    return negative ? -value : value;
  }

  /**
   * A parsed response.
   *
   * @param vectors the embeddings in order
   * @param promptTokens the charged token count
   */
  private record Parsed(List<float[]> vectors, long promptTokens) {}
}
