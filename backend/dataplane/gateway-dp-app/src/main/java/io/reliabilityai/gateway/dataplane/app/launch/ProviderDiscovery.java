package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModule;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModuleFactory;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderModuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

/**
 * Finds the provider module an operator asked for, without knowing which providers exist.
 *
 * <p>This is the mechanism that keeps the composition root provider-neutral: adapters are found on
 * the classpath through {@link ServiceLoader}, and selected by an opaque id from configuration. No
 * vendor is named here, and adding a provider means adding a jar, not editing this file.
 */
final class ProviderDiscovery {

  private ProviderDiscovery() {}

  /**
   * Builds the provider module named by the settings.
   *
   * @param settings the operator-supplied settings
   * @return the module, not yet started
   * @throws LaunchConfigurationException if no adapter matches, or if the choice is ambiguous
   */
  static ProviderModule module(final LaunchSettings settings) {
    final List<ProviderModuleFactory> available = new ArrayList<>();
    for (final ProviderModuleFactory factory : ServiceLoader.load(ProviderModuleFactory.class)) {
      available.add(factory);
    }
    if (available.isEmpty()) {
      throw new LaunchConfigurationException(
          "no provider adapter found on the classpath: a node cannot route without one");
    }
    final ProviderModuleFactory chosen = select(available, settings.providerId());
    return chosen.create(
        new ProviderModuleSettings(
            settings.providerBaseUri(),
            settings.model(),
            LaunchWiring.ROUTE_REF,
            settings.connectTimeout(),
            settings.requestTimeout()));
  }

  /**
   * Chooses among the discovered adapters.
   *
   * <p>An unnamed provider is allowed only when exactly one adapter is present. With two on the
   * classpath the node refuses to guess: silently picking the first would make which provider a
   * regulated request reached depend on classpath order.
   */
  private static ProviderModuleFactory select(
      final List<ProviderModuleFactory> available, final Optional<String> requested) {
    if (requested.isEmpty()) {
      if (available.size() > 1) {
        throw new LaunchConfigurationException(
            "GATEWAY_PROVIDER is not set and "
                + available.size()
                + " adapters are available: "
                + names(available));
      }
      return available.get(0);
    }
    final ProviderId wanted = ProviderId.of(requested.orElseThrow());
    return available.stream()
        .filter(factory -> factory.providerId().equals(wanted))
        .findFirst()
        .orElseThrow(
            () ->
                new LaunchConfigurationException(
                    "no adapter for GATEWAY_PROVIDER="
                        + wanted.value()
                        + "; available: "
                        + names(available)));
  }

  private static String names(final List<ProviderModuleFactory> available) {
    return available.stream()
        .map(factory -> factory.providerId().value())
        .sorted()
        .collect(Collectors.joining(", "));
  }
}
