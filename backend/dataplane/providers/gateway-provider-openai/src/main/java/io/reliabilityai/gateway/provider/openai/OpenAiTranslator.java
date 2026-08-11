package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTranslator;
import io.reliabilityai.gateway.dataplane.provider.api.TransportException;
import io.reliabilityai.gateway.dataplane.provider.api.TransportRequest;
import io.reliabilityai.gateway.dataplane.provider.api.TransportResponse;
import io.reliabilityai.gateway.dataplane.provider.domain.TransportFailureKind;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import io.reliabilityai.gateway.ports.ProviderTransportStream;
import io.reliabilityai.gateway.provider.openai.internal.Json;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The OpenAI anti-corruption layer (AD-007, Doc 25): canonical in, OpenAI wire out, canonical back.
 *
 * <p>This is the only place in the gateway that knows OpenAI's request shape, response shape or
 * status semantics. Everything upstream — router, reliability, governance, metering — sees
 * canonical types only, which is what makes swapping this adapter for another provider a no-diff
 * change for them.
 */
public final class OpenAiTranslator implements ProviderTranslator {

  private final OpenAiConfiguration configuration;
  private final OpenAiRequestMapper requestMapper;
  private final OpenAiResponseMapper responseMapper;

  /**
   * Creates the translator.
   *
   * @param configuration the adapter configuration
   * @param requestMapper the canonical → OpenAI mapper
   * @param responseMapper the OpenAI → canonical mapper
   */
  public OpenAiTranslator(
      final OpenAiConfiguration configuration,
      final OpenAiRequestMapper requestMapper,
      final OpenAiResponseMapper responseMapper) {
    this.configuration = Preconditions.requireNonNull(configuration, "configuration");
    this.requestMapper = Preconditions.requireNonNull(requestMapper, "requestMapper");
    this.responseMapper = Preconditions.requireNonNull(responseMapper, "responseMapper");
  }

  @Override
  public TransportRequest translateOut(
      final CanonicalRequest request,
      final CapabilityMapping capabilities,
      final PinnedVersion pinnedVersion) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(capabilities, "capabilities");
    Preconditions.requireNonNull(pinnedVersion, "pinnedVersion");

    // Streaming needs both: the model must support it, and the caller must have asked for it.
    final boolean streaming =
        capabilities.capabilities().contains("streaming")
            && "true".equals(request.params().get(OpenAiRequestMapper.PARAM_STREAM));
    final boolean embeddings = capabilities.capabilities().contains("embeddings");

    final String path =
        embeddings
            ? OpenAiConfiguration.EMBEDDINGS_PATH
            : OpenAiConfiguration.CHAT_COMPLETIONS_PATH;
    final String body =
        embeddings
            ? requestMapper.toEmbeddingsBody(request, capabilities)
            : requestMapper.toChatCompletionsBody(request, capabilities, streaming);

    // No Authorization header here: the credential is attached at send time from the live lease, so
    // a
    // retained TransportRequest can never carry key material (Doc 26 §17.1).
    final Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("Accept", streaming ? "text/event-stream" : "application/json");
    // Compression is requested for buffered responses only. A gzipped SSE body would reach
    // StreamGuard
    // as compressed bytes, which it would rightly judge corrupt — and decompressing mid-stream
    // would
    // mean re-framing the provider's bytes, destroying the very thing the guard verifies.
    if (configuration.compressionEnabled() && !streaming) {
      headers.put("Accept-Encoding", "gzip");
    }
    if (configuration.organization() != null && !configuration.organization().isBlank()) {
      headers.put("OpenAI-Organization", configuration.organization());
    }

    return new TransportRequest(
        path, headers, body.getBytes(StandardCharsets.UTF_8), pinnedVersion);
  }

  @Override
  public ProviderInvocationResult translateIn(final TransportResponse response) {
    Preconditions.requireNonNull(response, "response");

    if (response.status() < 200 || response.status() >= 300) {
      return new ProviderInvocationResult.Failed(
          responseMapper.toCanonicalError(response.status()));
    }
    if (response instanceof TransportResponse.Streamed streamed) {
      // The body is still framed. Hand it up with the decoder attached so STREAM_GUARD can verify
      // the
      // provider's own bytes before anything interprets them (Doc 18, AD-018).
      if (!(streamed.frames() instanceof ProviderTransportStream source)) {
        return new ProviderInvocationResult.Failed(responseMapper.malformed());
      }
      final OpenAiStreamingDecoder streamDecoder = new OpenAiStreamingDecoder(responseMapper);
      return new ProviderInvocationResult.StreamingTransport(
          source, payload -> streamDecoder.decode(new String(payload, StandardCharsets.UTF_8)));
    }
    if (!(response instanceof TransportResponse.Buffered buffered)) {
      throw new IllegalArgumentException("unsupported transport response");
    }
    try {
      return new ProviderInvocationResult.Unary(
          responseMapper.toCanonicalResponse(new String(buffered.body(), StandardCharsets.UTF_8)));
    } catch (final Json.JsonException malformed) {
      // A 200 with an unusable body is still a failure — never a half-built response.
      return new ProviderInvocationResult.Failed(responseMapper.malformed());
    }
  }

  @Override
  public CanonicalError classifyTransportFailure(final TransportException failure) {
    Preconditions.requireNonNull(failure, "failure");
    final TransportFailureKind kind = failure.kind();
    final String opaque = "openai:transport:" + kind.name().toLowerCase(java.util.Locale.ROOT);
    return switch (kind) {
      // Connect and IO faults are usually a node or path problem: another attempt may land
      // elsewhere.
      case CONNECT, IO -> new CanonicalError(ErrorCategory.TRANSPORT, Boolean.TRUE, opaque, true);
      case TIMEOUT -> new CanonicalError(ErrorCategory.TIMEOUT, Boolean.TRUE, opaque, true);
      // A TLS failure is a trust problem. Retrying cannot fix an untrusted chain, and retrying past
      // a
      // certificate error is exactly how a MITM gets a second chance.
      case TLS -> new CanonicalError(ErrorCategory.TRANSPORT, Boolean.FALSE, opaque, false);
      case CANCELLED -> new CanonicalError(ErrorCategory.TRANSPORT, Boolean.FALSE, opaque, false);
    };
  }
}
