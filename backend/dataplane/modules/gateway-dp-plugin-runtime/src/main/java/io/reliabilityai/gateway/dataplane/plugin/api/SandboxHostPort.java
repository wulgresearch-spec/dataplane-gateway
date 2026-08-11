package io.reliabilityai.gateway.dataplane.plugin.api;

import java.util.concurrent.Callable;

/**
 * The isolation substrate one plugin invocation runs on (Doc 28 §ISO, §REC).
 *
 * <p>The substrate owns two things and only two: <b>containment</b> and <b>budget enforcement</b>.
 * It does not know what a plugin is, what an extension point is, or what the work means. That
 * separation is what lets the process substrate and the in-process substrate be genuinely
 * interchangeable behind one port.
 *
 * <p>The task is a {@link Callable} running on the <em>host</em> side. For the in-process substrate
 * that callable is the plugin itself; for the process substrate it is the I/O plumbing that talks
 * to a child process where the untrusted code actually runs. Doc 28 ISO-1 is satisfied by {@link
 * #isolation()} reporting honestly which of the two this is, and by the runtime refusing to put
 * third-party code on a substrate that reports {@link IsolationLevel#IN_PROCESS}.
 */
public interface SandboxHostPort {

  /**
   * The containment this substrate actually provides.
   *
   * <p>Reported, never assumed, and never overclaimed (Doc 28 ISO-10). The runtime's admission
   * check reads this value, so a substrate that lied would be the single point where Doc 28's
   * isolation guarantee fails.
   *
   * @return the isolation level
   */
  IsolationLevel isolation();

  /**
   * Whether this substrate can run the given plugin type.
   *
   * @param type the plugin type
   * @return true if this substrate supports it
   */
  boolean supports(PluginType type);

  /**
   * Runs one unit of work under the budget, cancelling it on breach.
   *
   * <p>Must never propagate the task's exception. Every outcome — success, throw, overrun, quota
   * breach, cancellation — comes back as a {@link SandboxOutcome}, because Doc 28 §EPFC requires
   * the caller to proceed unweakened and a caller that has to catch cannot be relied upon to.
   *
   * @param invocation the plugin id, budget and deadline
   * @param task the host-side work
   * @param <T> the result type
   * @return the outcome, never null
   */
  <T> SandboxOutcome<T> run(SandboxInvocation invocation, Callable<T> task);

  /**
   * Releases everything this substrate holds. Idempotent.
   *
   * <p>Must terminate in-flight work rather than waiting for it: shutdown that waits on a plugin is
   * shutdown a plugin can veto.
   */
  void shutdown();
}
