package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.ProviderAdapterPort;
import java.util.Optional;

/**
 * A started provider: its adapter, its optional probe, and the resources it must release.
 *
 * <p>{@link AutoCloseable} because a provider that opens a connection pool has to be able to close
 * it, and the alternative — the gateway closing something it did not open — is how a shared HTTP
 * client ends up outliving the node that made it. Shutdown closes every instance it started, in
 * reverse registration order, and a provider that throws on close does not stop the others closing.
 *
 * @param providerId the provider this instance belongs to
 * @param adapter the canonical adapter Reliability invokes
 * @param probe the optional liveness check, absent when the provider offers none
 * @param resources the provider's own closeable transport, or a no-op when it holds none
 */
public record ProviderInstance(
    ProviderId providerId,
    ProviderAdapterPort adapter,
    Optional<ProviderProbe> probe,
    AutoCloseable resources)
    implements AutoCloseable {

  /** Validates the instance. */
  public ProviderInstance {
    Preconditions.requireNonNull(providerId, "providerId");
    Preconditions.requireNonNull(adapter, "adapter");
    Preconditions.requireNonNull(probe, "probe");
    Preconditions.requireNonNull(resources, "resources");
  }

  /**
   * Creates an instance holding no closeable resources.
   *
   * @param providerId the provider
   * @param adapter the canonical adapter
   * @param probe the optional probe
   * @return the instance
   */
  public static ProviderInstance of(
      final ProviderId providerId,
      final ProviderAdapterPort adapter,
      final Optional<ProviderProbe> probe) {
    return new ProviderInstance(providerId, adapter, probe, () -> {});
  }

  @Override
  public void close() throws Exception {
    resources.close();
  }
}
