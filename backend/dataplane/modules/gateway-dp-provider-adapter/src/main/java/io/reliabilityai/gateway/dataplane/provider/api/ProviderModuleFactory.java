package io.reliabilityai.gateway.dataplane.provider.api;

/**
 * How a composition root obtains a {@link ProviderModule} without naming one.
 *
 * <p>{@link ProviderModule} is the contract a provider implements; this is the contract that lets
 * the gateway <em>find</em> that implementation. Without it the promise on {@code ProviderModule} —
 * that adding a provider touches nothing in the composition root — cannot actually be kept: someone
 * still has to call {@code new SomeVendorModule(...)}, and the only place left to write that is the
 * composition root, which is exactly what provider neutrality forbids.
 *
 * <p>Implementations are discovered with {@link java.util.ServiceLoader}, so a provider ships its
 * adapter and a service declaration, and an operator selects one by {@link #providerId()} in
 * configuration. Adding the second provider is then a jar on the classpath and a string in the
 * environment.
 *
 * <p>Implementations must have a public no-argument constructor, and {@link #providerId()} must be
 * pure and constant — it is read during discovery, before anything is built.
 */
public interface ProviderModuleFactory {

  /**
   * The identity an operator selects this provider by.
   *
   * @return the provider identity, stable across versions
   */
  ProviderId providerId();

  /**
   * Builds the module from neutral settings.
   *
   * @param settings the operator-supplied settings
   * @return the provider module, not yet started
   */
  ProviderModule create(ProviderModuleSettings settings);
}
