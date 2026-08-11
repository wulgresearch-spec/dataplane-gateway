package io.reliabilityai.gateway.canonical.io;

/**
 * Usage class (Doc 18 CV-5, Doc 23). {@code AUTHORITATIVE} = provider returned exact figures;
 * {@code ESTIMATED} = provider-flagged estimate. Never fabricated (Doc 23 §D5).
 */
public enum UsageClass {
  /** Provider returned exact figures. */
  AUTHORITATIVE,
  /** Provider-flagged estimate; recorded only under explicit policy (Doc 23 §24). */
  ESTIMATED
}
