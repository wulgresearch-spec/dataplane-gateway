package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * A provider's liveness verdict, in neutral terms.
 *
 * <p>No HTTP status, no vendor error body, no SDK exception. Not every provider speaks HTTP, and a
 * verdict shaped around one transport is a verdict the next provider has to lie in. A provider
 * encodes whatever it learned into {@code detail}, which is an opaque, content-free code an
 * operator can grep for and nothing branches on.
 *
 * <p>{@link ContentFree}: this travels to operators and telemetry, so it carries ids and codes
 * only. {@code observedModels} is a list of model identifiers the provider reported, which is
 * diagnostic rather than authoritative — the capability snapshot decides what this node will route
 * to, and a model appearing here does not make it usable.
 *
 * @param providerId which provider was probed
 * @param healthy whether the provider answered successfully
 * @param detail an opaque, content-free outcome code
 * @param observedModels model identifiers the provider reported, sorted; diagnostic only
 */
public record ProviderHealth(
    ProviderId providerId, boolean healthy, String detail, List<String> observedModels)
    implements ContentFree {

  /** Validates the verdict. */
  public ProviderHealth {
    Preconditions.requireNonNull(providerId, "providerId");
    Preconditions.requireNonBlank(detail, "detail");
    observedModels = observedModels == null ? List.of() : List.copyOf(observedModels);
  }

  /**
   * A verdict for a provider that was never probed.
   *
   * <p>Distinct from unhealthy on purpose: "we did not ask" and "we asked and it failed" call for
   * different operator responses, and collapsing them makes an unconfigured probe look like an
   * outage.
   *
   * @param providerId the provider
   * @return the not-probed verdict
   */
  public static ProviderHealth notProbed(final ProviderId providerId) {
    return new ProviderHealth(providerId, false, "not-probed", List.of());
  }

  /**
   * An unhealthy verdict.
   *
   * @param providerId the provider
   * @param detail the opaque outcome code
   * @return the unhealthy verdict
   */
  public static ProviderHealth unhealthy(final ProviderId providerId, final String detail) {
    return new ProviderHealth(providerId, false, detail, List.of());
  }
}
