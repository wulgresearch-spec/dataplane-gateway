package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.ports.AttemptBudget;

/**
 * An optional liveness check a provider module can offer.
 *
 * <p>Optional because not every provider has a cheap reachability endpoint, and a platform that
 * required one would either exclude those providers or push them into faking it with a real
 * request. A provider without a probe simply reports {@link ProviderHealth#notProbed}.
 *
 * <p><b>Off the request path, always.</b> Nothing in routing or reliability consults health. A
 * probe that fed routing would make the router's decisions depend on the timing of a background
 * call, and a stale healthy verdict is exactly the input that sends traffic to a dead provider.
 * Reliability's circuit breaker already handles live failure from live evidence.
 *
 * <p>Takes a credential because an unauthenticated reachability check reports healthy while every
 * real request fails on a rotated key — the failure mode most worth catching.
 */
@FunctionalInterface
public interface ProviderProbe {

  /**
   * Probes the provider.
   *
   * @param credential a live credential lease
   * @param budget the transport budget for the probe
   * @return the verdict — implementations report failure rather than throwing
   */
  ProviderHealth probe(CredentialLease credential, AttemptBudget budget);
}
