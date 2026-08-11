package io.reliabilityai.gateway.canonical.decision;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * A route target selected by the Router (C1, Doc 19) and executed by Reliability (Doc 20) via the
 * adapter (Doc 25). Keyed by canonical model id + an opaque provider-route reference; downstream
 * code never branches on provider name (AD-007, Doc 25 §7.1 registry dispatch).
 *
 * @param canonicalModelId the canonical model id (Doc 19 §9.2)
 * @param providerRouteRef the opaque provider-route reference (adapter dispatch key)
 */
public record RouteTarget(CanonicalModelId canonicalModelId, String providerRouteRef) {

  /** Compact constructor validating the model id and route reference. */
  public RouteTarget {
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonBlank(providerRouteRef, "providerRouteRef");
  }
}
