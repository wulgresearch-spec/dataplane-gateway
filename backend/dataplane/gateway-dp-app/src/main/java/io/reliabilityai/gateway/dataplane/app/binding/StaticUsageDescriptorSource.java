package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.metering.api.UsageDescriptorPort;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageDescriptor;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the per-model {@link UsageDescriptor} (how a provider's reported units map to billable
 * units) from an immutable operator-supplied table (Doc 23). An unknown model returns empty, which
 * makes metering record the attempt as unrecorded rather than normalising against a guessed
 * descriptor.
 */
public final class StaticUsageDescriptorSource implements UsageDescriptorPort {

  private final Map<CanonicalModelId, UsageDescriptor> descriptors;

  /**
   * Creates the source.
   *
   * @param descriptors the immutable per-model descriptor table
   */
  public StaticUsageDescriptorSource(final Map<CanonicalModelId, UsageDescriptor> descriptors) {
    this.descriptors = Map.copyOf(Preconditions.requireNonNull(descriptors, "descriptors"));
  }

  @Override
  public Optional<UsageDescriptor> descriptorFor(final CanonicalModelId canonicalModelId) {
    if (canonicalModelId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(descriptors.get(canonicalModelId));
  }
}
