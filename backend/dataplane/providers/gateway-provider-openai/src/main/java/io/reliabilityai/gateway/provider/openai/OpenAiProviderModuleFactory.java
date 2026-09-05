package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModule;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModuleFactory;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModuleSettings;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderRoute;
import java.net.http.HttpClient;
import java.util.List;

/**
 * Builds this provider's module from neutral operator settings.
 *
 * <p>Discovered by {@link java.util.ServiceLoader}, so a composition root can stand this provider
 * up without importing anything in this package. Everything vendor-shaped stays here: the pinned
 * API version, the HTTP version to negotiate, and the capability declaration for a
 * current-generation chat model.
 */
public final class OpenAiProviderModuleFactory implements ProviderModuleFactory {

  /**
   * The API version pinned on routes this factory declares.
   *
   * <p>Fixed rather than operator-supplied: it pairs with the request and response mappers in this
   * module, and letting an operator set it would invite a version this adapter cannot actually
   * speak.
   */
  private static final PinnedVersion API_VERSION = new PinnedVersion("2024-10-01");

  /** The context ceiling declared for a route built by this factory. */
  private static final long MAX_CONTEXT_TOKENS = 128_000L;

  /** The output ceiling declared for a route built by this factory. */
  private static final long MAX_OUTPUT_TOKENS = 16_384L;

  /** Required by {@link java.util.ServiceLoader}. */
  public OpenAiProviderModuleFactory() {
    // No state: every setting arrives with the create() call.
  }

  @Override
  public ProviderId providerId() {
    return OpenAiProviderModule.PROVIDER_ID;
  }

  @Override
  public ProviderModule create(final ProviderModuleSettings settings) {
    final OpenAiConfiguration configuration =
        new OpenAiConfiguration(
            settings.baseUri(),
            API_VERSION,
            settings.connectTimeout(),
            settings.requestTimeout(),
            null,
            true);
    return new OpenAiProviderModule(
        configuration,
        HttpClient.Version.HTTP_1_1,
        List.of(
            new ProviderRoute(
                settings.model(),
                settings.routeRef(),
                OpenAiProviderModule.chatCapabilities(),
                API_VERSION,
                MAX_CONTEXT_TOKENS,
                MAX_OUTPUT_TOKENS)));
  }
}
