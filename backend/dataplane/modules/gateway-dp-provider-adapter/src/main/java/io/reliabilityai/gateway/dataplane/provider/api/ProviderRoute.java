package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * One canonical model reachable on one provider route, and what it can do there.
 *
 * <p>The unit of declaration is <b>model on route</b>, not provider, because capability is not a
 * property of a vendor. The same vendor serves models that stream and models that do not, models
 * that take images and models that take only text. A provider-level capability list would be the
 * union of everything the vendor offers anywhere, which is exactly the list that routes a vision
 * request to a text-only model.
 *
 * <p>{@code maxContextTokens} and {@code maxOutputTokens} are declared here too. They are
 * capabilities in every sense that matters — a route either can or cannot accept a 200k-token
 * prompt — and the router already filters on context length, so keeping them beside the capability
 * set stops the two from being published from different places and disagreeing.
 *
 * @param canonicalModelId the canonical model this route serves
 * @param providerRouteRef the opaque adapter dispatch key
 * @param capabilities what this model can do on this route
 * @param apiVersion the pinned provider API version for this route
 * @param maxContextTokens the largest input this route accepts, in tokens
 * @param maxOutputTokens the largest completion this route produces, in tokens
 */
public record ProviderRoute(
    CanonicalModelId canonicalModelId,
    String providerRouteRef,
    CapabilitySet capabilities,
    PinnedVersion apiVersion,
    long maxContextTokens,
    long maxOutputTokens) {

  /** Validates the route declaration. */
  public ProviderRoute {
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonBlank(providerRouteRef, "providerRouteRef");
    Preconditions.requireNonNull(capabilities, "capabilities");
    Preconditions.requireNonNull(apiVersion, "apiVersion");
    Preconditions.requireNonNegative(maxContextTokens, "maxContextTokens");
    Preconditions.requireNonNegative(maxOutputTokens, "maxOutputTokens");
  }
}
