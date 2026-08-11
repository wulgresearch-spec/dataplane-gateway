package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * How much the operator trusts the code in a plugin snapshot (Doc 28 §ISO).
 *
 * <p>This is the one input that decides whether the in-JVM substrate is even eligible. It is
 * declared in the C12-signed manifest, so a plugin author cannot promote their own code: changing
 * the tier changes the signed bytes and fails verification (Doc 28 STC-3).
 */
public enum TrustTier {

  /** Code shipped and signed as part of the gateway itself; may run in the host JVM. */
  FIRST_PARTY,

  /** Everything else. Must run behind the process boundary, whatever its declared type. */
  THIRD_PARTY
}
