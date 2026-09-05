package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.common.Preconditions;
import java.net.URI;
import java.time.Duration;

/**
 * The neutral settings an operator supplies to build a provider module.
 *
 * <p>Every field here is something an operator knows without knowing which vendor is behind the
 * route: where to reach it, which canonical model to serve, what to call the route, and how long to
 * wait. Anything vendor-shaped — the wire format, the auth header, the path, the model naming
 * convention — is the module's business and never appears here.
 *
 * @param baseUri the provider API root
 * @param model the canonical model this node serves
 * @param routeRef the opaque route reference the model is reachable on
 * @param connectTimeout the connect budget
 * @param requestTimeout the per-request budget
 */
public record ProviderModuleSettings(
    URI baseUri,
    CanonicalModelId model,
    String routeRef,
    Duration connectTimeout,
    Duration requestTimeout) {

  /** Validates the settings. */
  public ProviderModuleSettings {
    Preconditions.requireNonNull(baseUri, "baseUri");
    Preconditions.requireNonNull(model, "model");
    Preconditions.requireNonBlank(routeRef, "routeRef");
    Preconditions.requireNonNull(connectTimeout, "connectTimeout");
    Preconditions.requireNonNull(requestTimeout, "requestTimeout");
  }
}
