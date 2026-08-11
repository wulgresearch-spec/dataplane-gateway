package io.reliabilityai.gateway.dataplane.schemalock.api;

/**
 * A streaming structured-output session (Doc 17 §8/§18). It consumes StreamGuard-reconstructed
 * deltas, assembles the logical partial object, runs advisory incremental validation (fail-fast),
 * and — only on a clean transport completion — runs the <b>authoritative</b> completion validation.
 * The terminal success is <b>gated</b> on that completion verdict (Doc 17 §8/§43.1 RB-8, SL-A10): a
 * partial is never labeled conformant mid-stream. Per request; never merges partials across
 * duplicate streams (Doc 17 §24.1/§29.1).
 */
public interface StructuredStreamSession {

  /**
   * Drives the stream to its terminal outcome and returns the gated canonical output (Doc 17
   * §21/§30). A conformant result is returned <b>only</b> after a passed completion verdict; any
   * transport failure or non-conformance surfaces (SL-INV).
   *
   * @return the terminal canonical output
   */
  CanonicalOutput awaitCompletion();
}
