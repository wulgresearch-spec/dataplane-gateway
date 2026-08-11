package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.canonical.capability.ProviderCapability;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderDescriptor;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderHealth;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderInstance;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModule;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderProbe;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRoute;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRuntimeContext;
import io.reliabilityai.gateway.dataplane.provider.application.ProviderAdapterService;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The reference provider module — everything a provider has to bring, and nothing else.
 *
 * <p>This class is the answer to "what does adding a provider actually cost". It declares which
 * models it serves and what each can do, and it builds its own transport, translator and probe. It
 * touches no gateway type beyond the SPI. The router, the governance engine, the pipeline and the
 * composition root do not know it exists — before this migration, the composition root constructed
 * this vendor's configuration, HTTP client, transport, health check and translator by name, which
 * meant adding a second provider required editing the class that wires the whole gateway.
 *
 * <p><b>It owns its transport.</b> The HTTP client is built here, from this provider's own
 * configuration, and closed through {@link ProviderInstance#close}. The gateway deliberately does
 * not hand providers a client: the moment it does, it owns an opinion about protocol version and
 * pooling that the first non-HTTP provider has to work around.
 *
 * <p><b>Capabilities are declared per model, not per vendor.</b> The embeddings route and the chat
 * route are the same vendor and share nothing behaviourally — declaring at vendor level would
 * produce a union that routes a vision request to an embeddings endpoint.
 */
public final class OpenAiProviderModule implements ProviderModule {

  /** The opaque identity operators see in logs and configuration. Nothing branches on it. */
  public static final ProviderId PROVIDER_ID = ProviderId.of("openai");

  private final OpenAiConfiguration configuration;
  private final List<ProviderRoute> routes;
  private final HttpClient.Version httpVersion;

  /**
   * Creates the module with explicitly declared routes.
   *
   * @param configuration the endpoint, timeouts and pinned API version
   * @param httpVersion the HTTP protocol version to negotiate
   * @param routes the model-on-route declarations this module serves
   */
  public OpenAiProviderModule(
      final OpenAiConfiguration configuration,
      final HttpClient.Version httpVersion,
      final List<ProviderRoute> routes) {
    this.configuration = Preconditions.requireNonNull(configuration, "configuration");
    this.httpVersion = Preconditions.requireNonNull(httpVersion, "httpVersion");
    this.routes = List.copyOf(Preconditions.requireNonNull(routes, "routes"));
  }

  /**
   * The capabilities of a current-generation chat model on this provider.
   *
   * <p>A helper, not a policy. The authority on what a route may do is the operator's published
   * capability snapshot; this exists so a declaration for a typical model is one call rather than
   * fifteen enum constants, and so the reference module shows what a complete declaration looks
   * like.
   *
   * @return the chat capability set
   */
  public static CapabilitySet chatCapabilities() {
    return CapabilitySet.of(
        ProviderCapability.TEXT_GENERATION,
        ProviderCapability.CHAT_COMPLETION,
        ProviderCapability.STREAMING,
        ProviderCapability.FUNCTION_CALLING,
        ProviderCapability.PARALLEL_TOOL_CALLS,
        ProviderCapability.JSON_MODE,
        ProviderCapability.JSON_SCHEMA,
        ProviderCapability.VISION,
        ProviderCapability.MULTIMODAL,
        ProviderCapability.LONG_CONTEXT,
        ProviderCapability.PROMPT_CACHE,
        ProviderCapability.BATCH,
        ProviderCapability.FILES,
        ProviderCapability.TOKEN_COUNTING);
  }

  /**
   * The capabilities of an embeddings route on this provider.
   *
   * <p>Deliberately tiny. An embeddings endpoint does not stream, does not call tools and does not
   * accept images, and declaring otherwise is how a vision request reaches a model that cannot
   * serve it.
   *
   * @return the embeddings capability set
   */
  public static CapabilitySet embeddingCapabilities() {
    return CapabilitySet.of(ProviderCapability.EMBEDDINGS, ProviderCapability.FILES);
  }

  /**
   * Declares a chat route.
   *
   * @param model the canonical model
   * @param routeRef the opaque route reference
   * @param configuration the module configuration, for its pinned API version
   * @param maxContextTokens the route's input ceiling
   * @param maxOutputTokens the route's completion ceiling
   * @return the route declaration
   */
  public static ProviderRoute chatRoute(
      final CanonicalModelId model,
      final String routeRef,
      final OpenAiConfiguration configuration,
      final long maxContextTokens,
      final long maxOutputTokens) {
    Preconditions.requireNonNull(configuration, "configuration");
    return new ProviderRoute(
        model,
        routeRef,
        chatCapabilities(),
        configuration.apiVersion(),
        maxContextTokens,
        maxOutputTokens);
  }

  @Override
  public ProviderDescriptor descriptor() {
    return new ProviderDescriptor(PROVIDER_ID, routes);
  }

  @Override
  public ProviderInstance start(final ProviderRuntimeContext context) {
    Preconditions.requireNonNull(context, "context");

    // One client for this provider's lifetime — that is what gives connection pooling and HTTP/2
    // multiplexing. Closed through the returned instance, so the gateway never closes what it did
    // not
    // open and never leaks what it did.
    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(httpVersion)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(configuration.connectTimeout())
            .build();

    final OpenAiProviderTransport transport =
        new OpenAiProviderTransport(httpClient, configuration, new OpenAiAuthentication());
    final OpenAiHealthCheck healthCheck = new OpenAiHealthCheck(transport, configuration);

    final ProviderAdapterService adapter =
        new ProviderAdapterService(
            new OpenAiTranslator(
                configuration, new OpenAiRequestMapper(), new OpenAiResponseMapper()),
            transport,
            context.credentials(),
            context.capabilities(),
            context.telemetry());

    return new ProviderInstance(
        PROVIDER_ID, adapter, Optional.of(probe(healthCheck)), httpClient::close);
  }

  /** Adapts this provider's health verdict onto the neutral one the platform reports. */
  private static ProviderProbe probe(final OpenAiHealthCheck healthCheck) {
    return (credential, budget) -> {
      final OpenAiHealthCheck.Health verdict = healthCheck.probe(credential, budget);
      return new ProviderHealth(
          PROVIDER_ID,
          verdict.healthy(),
          // The HTTP status becomes part of an opaque detail code rather than a field: not every
          // provider speaks HTTP, and a neutral verdict must not have a shape only this one can
          // fill.
          verdict.detail() + (verdict.status() == 0 ? "" : ":" + verdict.status()),
          new ArrayList<>(verdict.models()));
    };
  }
}
