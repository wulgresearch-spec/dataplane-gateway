package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * How strongly a substrate contains the code it runs (Doc 28 §ISO).
 *
 * <p>Doc 28 ISO-8 through ISO-10 require this to be stated without overclaim, so the levels
 * describe what is actually true of each substrate rather than what would be reassuring. Notably,
 * {@link #IN_PROCESS} is honest that a runaway plugin can still take the host down: the JVM offers
 * no per-thread memory cap and no way to stop a thread that will not be interrupted (AD-023 removed
 * the SecurityManager, and it would not have helped here anyway).
 */
public enum IsolationLevel {

  /**
   * Same JVM, same heap, separate virtual thread. Enforces wall-clock deadlines and interrupt-based
   * cancellation; <b>cannot</b> enforce a memory ceiling or contain a crash. Admissible only for
   * {@link TrustTier#FIRST_PARTY} code (Doc 28 ISO-1).
   */
  IN_PROCESS(false),

  /**
   * Separate OS process. No shared heap with the request path; a crash, an escape attempt or memory
   * exhaustion is contained to that process and the process can always be killed. This is the
   * boundary Doc 28 ISO-1/ISO-2 requires for untrusted code — strong containment with a bounded,
   * non-zero residual (ISO-9), not an absolute sandbox.
   */
  PROCESS(true);

  private final boolean containsUntrustedCode;

  IsolationLevel(final boolean containsUntrustedCode) {
    this.containsUntrustedCode = containsUntrustedCode;
  }

  /**
   * Whether this level may host third-party code.
   *
   * @return true only for levels that put a real boundary around the code (Doc 28 ISO-1)
   */
  public boolean containsUntrustedCode() {
    return containsUntrustedCode;
  }
}
