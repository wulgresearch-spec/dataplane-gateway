package io.reliabilityai.gateway.dataplane.metering.domain;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * A versioned, immutable usage descriptor (Doc 23 §9/§10, UME-D8) — the canonical, provider-neutral
 * definition of <b>which units are metered</b> for a canonical model/capability, from the C5 usage-
 * descriptor snapshot (AD-022, authored by C5-CP). The engine consumes it read-only and <b>stamps
 * its version</b> on every fact for deterministic replay/reproduction (Doc 23 §36). A missing
 * descriptor ⇒ fail closed (Doc 23 §38). Immutable.
 *
 * @param canonicalModelId the canonical model this descriptor defines units for
 * @param version the pinned descriptor version (stamped on every fact)
 */
public record UsageDescriptor(CanonicalModelId canonicalModelId, String version) {

  /** Compact constructor validating fields. */
  public UsageDescriptor {
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonBlank(version, "version");
  }
}
