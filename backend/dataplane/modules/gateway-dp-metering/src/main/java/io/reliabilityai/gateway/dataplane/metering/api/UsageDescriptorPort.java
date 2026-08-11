package io.reliabilityai.gateway.dataplane.metering.api;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.dataplane.metering.domain.UsageDescriptor;
import java.util.Optional;

/**
 * The usage-descriptor seam (Doc 23 §7/§9, AD-022). Delivers the versioned, provider-neutral {@link
 * UsageDescriptor} (authored by C5-CP) from the cached snapshot — the engine consumes it read-only
 * and stamps its version on every fact. A missing descriptor ⇒ the engine fails closed ({@code
 * MISSING_DESCRIPTOR}, Doc 23 §38).
 */
public interface UsageDescriptorPort {

  /**
   * Returns the pinned usage descriptor for a canonical model (Doc 23 §9).
   *
   * @param canonicalModelId the canonical model id
   * @return the descriptor, or empty ⇒ fail closed
   */
  Optional<UsageDescriptor> descriptorFor(CanonicalModelId canonicalModelId);
}
