package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A disagreement found while validating a provider's declaration against the published capability
 * snapshot.
 *
 * <p>The reason this type exists is {@link Kind#CAPABILITY_OVERCLAIM}. Everything else here is
 * housekeeping; that one is a real bug class the gateway previously had no way to notice. If the
 * operator publishes a snapshot saying a route streams and the module that serves that route does
 * not declare streaming, the router will happily select it for a streaming request and the adapter
 * will fail — once per request, in production, with the caller paying. Catching it at startup turns
 * a recurring runtime failure into a line in a startup report.
 *
 * @param providerId the provider the fault belongs to
 * @param routeRef the route reference, or {@code "-"} when the fault is provider-wide
 * @param kind what is wrong
 * @param detail an operator-facing, content-free explanation
 */
public record ProviderFault(ProviderId providerId, String routeRef, Kind kind, String detail) {

  /** The route reference used when a fault concerns the whole provider. */
  public static final String NO_ROUTE = "-";

  /** Validates the fault. */
  public ProviderFault {
    Preconditions.requireNonNull(providerId, "providerId");
    Preconditions.requireNonBlank(routeRef, "routeRef");
    Preconditions.requireNonNull(kind, "kind");
    Preconditions.requireNonBlank(detail, "detail");
  }

  /** What kind of disagreement was found, and whether it is safe to serve through. */
  public enum Kind {

    /**
     * The published snapshot grants capabilities the module does not declare. <b>Fatal.</b> Routing
     * will select this route for work its adapter cannot perform.
     */
    CAPABILITY_OVERCLAIM(true),

    /**
     * The module declares capabilities the published snapshot omits. Not fatal — the extra ability
     * is simply unused, because routing follows the snapshot. Worth reporting: it usually means a
     * snapshot was not updated when the module was.
     */
    CAPABILITY_UNDERCLAIM(false),

    /**
     * The module declares a route the published snapshot does not contain. Not fatal — the route is
     * unreachable, because the router only knows what the snapshot published.
     */
    ROUTE_NOT_PUBLISHED(false),

    /** Two modules claim the same provider id. <b>Fatal</b> for both — neither can be trusted. */
    DUPLICATE_PROVIDER(true),

    /**
     * Two modules claim the same route reference. <b>Fatal</b> — dispatch is keyed on the route, so
     * one would silently shadow the other.
     */
    DUPLICATE_ROUTE(true),

    /** The module threw while starting. <b>Fatal.</b> */
    START_FAILED(true);

    private final boolean fatal;

    Kind(final boolean fatal) {
      this.fatal = fatal;
    }

    /**
     * Whether this fault prevents the provider being dispatched to.
     *
     * @return {@code true} when the provider must not serve traffic
     */
    public boolean fatal() {
      return fatal;
    }
  }

  /**
   * Whether this fault prevents dispatch.
   *
   * @return {@code true} when fatal
   */
  public boolean fatal() {
    return kind.fatal();
  }
}
