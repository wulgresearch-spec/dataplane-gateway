package io.reliabilityai.gateway.canonical.io;

/**
 * Canonical, provider-neutral error categories (Doc 25 §16.1). Reliability (Doc 20) maps a category
 * to retry/failover policy; the adapter classifies the shape only, never the policy.
 *
 * <p>Fixed and stable (Doc 12 §27); provider-native codes are carried opaquely and never exposed
 * (Doc 25 §37, AD-007).
 */
public enum ErrorCategory {
  /** Connection / DNS / TLS failure before a provider verdict. */
  TRANSPORT,
  /** Per-attempt transport budget / deadline exhausted (Doc 25 §17.1). */
  TIMEOUT,
  /** Provider throttling / quota signal. */
  RATE_LIMITED,
  /** Provider 5xx / overloaded / capacity. */
  PROVIDER_UNAVAILABLE,
  /** Provider 4xx / invalid-request as the provider reports it. */
  PROVIDER_REJECTED,
  /** Provider rejected the credential. */
  AUTH_FAILED,
  /** Provider policy/safety block. */
  CONTENT_FILTERED,
  /** Provider output untranslatable to canonical. */
  MALFORMED_RESPONSE,
  /** Conservative default (non-success); never fabricated. */
  UNKNOWN
}
