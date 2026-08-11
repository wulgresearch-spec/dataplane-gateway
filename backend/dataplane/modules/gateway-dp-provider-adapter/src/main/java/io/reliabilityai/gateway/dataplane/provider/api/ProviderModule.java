package io.reliabilityai.gateway.dataplane.provider.api;

/**
 * The provider SPI — the entire surface a new provider has to implement.
 *
 * <p>Two methods. One says what the provider can do; the other builds the thing that does it. That
 * is the whole contract, and its smallness is the deliverable: bringing up a new provider means
 * writing an implementation of this interface and a {@link ProviderTranslator}, and touching
 * <b>nothing</b> in the router, the governance engine, the pipeline or the composition root. Before
 * this existed, the composition root constructed a specific vendor's configuration, transport,
 * health check and translator by name, so "add a provider" meant editing the class that wires the
 * entire gateway.
 *
 * <p><b>{@link #descriptor()} must be pure and constant.</b> It is called during startup validation
 * and may be called again by operator tooling; two calls must return the same declaration. It must
 * not perform I/O and must not consult the provider's API — capabilities are declared, never probed
 * (Doc 25 CAP-3). A descriptor that changed between calls would make the startup validation a
 * statement about a moment rather than about the provider.
 *
 * <p><b>{@link #start} may open resources</b> and is called once, during runtime construction.
 * Whatever it opens comes back inside the {@link ProviderInstance} so shutdown can close it.
 */
public interface ProviderModule {

  /**
   * What this provider is and what its routes can do. Pure, constant, and free of I/O.
   *
   * @return the provider's declaration
   */
  ProviderDescriptor descriptor();

  /**
   * Builds the provider's adapter from the neutral collaborators the gateway supplies.
   *
   * <p>Called once at startup. An implementation that cannot start — an unreachable configuration,
   * a missing setting — should throw; the runtime records the provider as failed and refuses to
   * bind it rather than registering an adapter that will fail every request.
   *
   * @param context the neutral collaborators
   * @return the started provider
   */
  ProviderInstance start(ProviderRuntimeContext context);
}
