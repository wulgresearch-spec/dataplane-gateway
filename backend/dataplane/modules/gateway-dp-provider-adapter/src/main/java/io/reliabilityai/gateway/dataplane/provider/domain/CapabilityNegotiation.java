package io.reliabilityai.gateway.dataplane.provider.domain;

import io.reliabilityai.gateway.canonical.capability.CapabilitySet;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The answer to "can this route do what this request needs", with the reason attached.
 *
 * <p>Carrying the missing set rather than just a boolean is the difference between a routing
 * failure an operator can act on and one they have to reproduce. "No candidate matched" sends
 * someone reading snapshots; "no candidate offered {@code json_schema}" tells them what to publish.
 *
 * @param satisfied whether every required capability is offered
 * @param required what the request asked for
 * @param offered what the route declares
 * @param missing what the route lacks — empty exactly when {@code satisfied}
 */
public record CapabilityNegotiation(
    boolean satisfied, CapabilitySet required, CapabilitySet offered, CapabilitySet missing) {

  /** Validates the negotiation and its internal consistency. */
  public CapabilityNegotiation {
    Preconditions.requireNonNull(required, "required");
    Preconditions.requireNonNull(offered, "offered");
    Preconditions.requireNonNull(missing, "missing");
    if (satisfied != missing.isEmpty()) {
      throw new IllegalArgumentException("satisfied must agree with an empty missing set");
    }
  }

  /**
   * Negotiates required against offered.
   *
   * @param required what the request needs
   * @param offered what the route declares
   * @return the negotiation, with any shortfall named
   */
  public static CapabilityNegotiation negotiate(
      final CapabilitySet required, final CapabilitySet offered) {
    Preconditions.requireNonNull(required, "required");
    Preconditions.requireNonNull(offered, "offered");
    final CapabilitySet missing = offered.missingFrom(required);
    return new CapabilityNegotiation(missing.isEmpty(), required, offered, missing);
  }

  /**
   * The capabilities the route offers that this request actually uses — the negotiated set.
   *
   * <p>Not the same as everything the route can do. A route that supports vision, batch and
   * reasoning serving a plain chat request has negotiated only chat, and recording the whole
   * declaration would make the audit trail claim the request used capabilities it never touched.
   *
   * @return the intersection of required and offered
   */
  public CapabilitySet negotiated() {
    return offered.intersect(required);
  }
}
