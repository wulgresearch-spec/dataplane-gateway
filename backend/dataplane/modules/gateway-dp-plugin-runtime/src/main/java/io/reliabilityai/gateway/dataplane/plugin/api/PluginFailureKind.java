package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.io.ErrorCategory;

/**
 * Why a plugin invocation was isolated (Doc 28 §37, §EPFC).
 *
 * <p>Each kind carries its own mapping into the canonical {@link ErrorCategory} the Reliability
 * Engine reasons about (Doc 20), and its own answer to "may this be retried". The two are separate
 * judgements: a permission denial and a plugin bug are both permanent, but for different reasons,
 * and conflating them would lose the distinction the operator needs.
 *
 * <p>The retryability answers are deliberately conservative. {@link #UNKNOWN} is not retryable,
 * because retrying a failure nobody understood is how a single bad invocation becomes a storm.
 */
public enum PluginFailureKind {

  /** The invocation exceeded its wall-clock deadline (Doc 28 REC-2). */
  TIMEOUT(ErrorCategory.TIMEOUT, true),

  /** The caller or the enclosing request cancelled the invocation. */
  CANCELLED(ErrorCategory.TIMEOUT, false),

  /** A CPU, memory or IO quota was breached (Doc 28 REC-4). */
  RESOURCE_EXCEEDED(ErrorCategory.PROVIDER_UNAVAILABLE, false),

  /** The mediated network call failed below the application layer. */
  NETWORK(ErrorCategory.TRANSPORT, true),

  /** The plugin attempted something its manifest never granted (Doc 28 PRT-D5). */
  PERMISSION(ErrorCategory.AUTH_FAILED, false),

  /** The plugin threw, or returned something structurally unusable. */
  PLUGIN_BUG(ErrorCategory.PROVIDER_REJECTED, false),

  /** The plugin process died, or the host substrate failed to contain it. */
  PANIC(ErrorCategory.PROVIDER_UNAVAILABLE, false),

  /** Anything not positively classified. Never retried, never assumed benign. */
  UNKNOWN(ErrorCategory.UNKNOWN, false);

  private final ErrorCategory category;
  private final boolean retryable;

  PluginFailureKind(final ErrorCategory category, final boolean retryable) {
    this.category = category;
    this.retryable = retryable;
  }

  /**
   * The canonical error category the Reliability Engine reasons about (Doc 20, Doc 25 §16.1).
   *
   * @return the mapped category
   */
  public ErrorCategory category() {
    return category;
  }

  /**
   * Whether another attempt could plausibly succeed.
   *
   * <p>Advisory only. Doc 28 REC-3 keeps retry policy with the Reliability Engine; this states the
   * shape of the failure, never the policy.
   *
   * @return true if the failure is transient in kind
   */
  public boolean retryable() {
    return retryable;
  }

  /**
   * Whether this failure is a Doc 28 violation attempt rather than an ordinary fault.
   *
   * <p>A violation is escalated and audited at a higher severity (Doc 28 §EPFC): a plugin reaching
   * for something it was never granted is a security event, whereas a timeout is a Tuesday.
   *
   * @return true for failures that indicate an attempted invariant breach
   */
  public boolean violation() {
    return this == PERMISSION || this == PANIC;
  }
}
