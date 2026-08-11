package io.reliabilityai.gateway.dataplane.router.api;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;

/**
 * A provider/model's <b>canonical</b> published capabilities and routing attributes, from the
 * Provider Registry snapshot (Doc 19 §6/§9.2, AD-007/AD-022). The Router matches only on these
 * canonical fields; it never reads a provider-native field or branches on a provider name (Doc 19
 * PR-D1/PR-A5). The {@code candidateId} and {@code providerRouteRef} are opaque route keys, not
 * provider identities. Immutable; sets defensively copied.
 *
 * @param candidateId the opaque candidate route id (never a provider name)
 * @param canonicalModelId the canonical model id this candidate serves
 * @param providerRouteRef the opaque adapter dispatch key
 * @param supportedCapabilities canonical capability identifiers this candidate supports (Doc 19
 *     §9.2)
 * @param maxContextLength the maximum context length
 * @param complianceAttestations canonical compliance regimes this candidate attests (e.g. HIPAA,
 *     SOC2)
 * @param allowedRegions residency regions this candidate may serve (AD-014)
 * @param costMicros normalized cost in integer micro-units (never billing; from cost snapshot)
 * @param availabilityScore health/availability score in {@code [0.0, 1.0]} (from health snapshot)
 * @param expectedLatencyMillis expected added latency in ms (from latency/health snapshot)
 * @param circuitOpen whether the reliability circuit is open (from reliability snapshot)
 */
public record CapabilityDescriptor(
    String candidateId,
    CanonicalModelId canonicalModelId,
    String providerRouteRef,
    Set<String> supportedCapabilities,
    int maxContextLength,
    Set<String> complianceAttestations,
    Set<String> allowedRegions,
    long costMicros,
    double availabilityScore,
    long expectedLatencyMillis,
    boolean circuitOpen) {

  /** Compact constructor validating fields and defensively copying sets. */
  public CapabilityDescriptor {
    Preconditions.requireNonBlank(candidateId, "candidateId");
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonBlank(providerRouteRef, "providerRouteRef");
    supportedCapabilities =
        Set.copyOf(Preconditions.requireNonNull(supportedCapabilities, "supportedCapabilities"));
    complianceAttestations =
        Set.copyOf(Preconditions.requireNonNull(complianceAttestations, "complianceAttestations"));
    allowedRegions = Set.copyOf(Preconditions.requireNonNull(allowedRegions, "allowedRegions"));
    Preconditions.requireNonNegative(maxContextLength, "maxContextLength");
    Preconditions.requireNonNegative(costMicros, "costMicros");
    Preconditions.requireNonNegative(expectedLatencyMillis, "expectedLatencyMillis");
    if (availabilityScore < 0.0 || availabilityScore > 1.0) {
      throw new IllegalArgumentException("availabilityScore must be within [0.0, 1.0]");
    }
  }
}
