package io.reliabilityai.gateway.dataplane.plugin.domain;

import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Classifies a plugin failure into the kinds the Reliability Engine reasons about (Doc 28 §37).
 *
 * <p>Classification looks at the <b>type</b> of what went wrong and never at its message. A
 * plugin's exception message is arbitrary third-party text that may contain whatever it was
 * processing, and string-matching on it would both leak content into a decision and let a plugin
 * choose its own classification by wording its error carefully.
 *
 * <p>Unrecognized throwables become {@link PluginFailureKind#PLUGIN_BUG}, not {@code UNKNOWN}: the
 * plugin threw something the host does not model, which is a defect in the plugin. {@code UNKNOWN}
 * is reserved for failures with no throwable at all.
 */
public final class FailureClassifier {

  private FailureClassifier() {}

  /**
   * Classifies a throwable a plugin produced.
   *
   * @param failure the throwable, which may be null
   * @return the classified kind
   */
  public static PluginFailureKind classify(final Throwable failure) {
    if (failure == null) {
      return PluginFailureKind.UNKNOWN;
    }
    // Errors are the host's problem, not the plugin's logic: OOM and stack exhaustion mean the
    // invocation consumed more than the substrate could give it.
    if (failure instanceof OutOfMemoryError || failure instanceof StackOverflowError) {
      return PluginFailureKind.RESOURCE_EXCEEDED;
    }
    if (failure instanceof Error) {
      return PluginFailureKind.PANIC;
    }
    if (failure instanceof InterruptedException || failure instanceof CancellationException) {
      return PluginFailureKind.CANCELLED;
    }
    if (failure instanceof TimeoutException) {
      return PluginFailureKind.TIMEOUT;
    }
    if (failure instanceof SecurityException || failure instanceof AccessDeniedException) {
      return PluginFailureKind.PERMISSION;
    }
    if (failure instanceof IOException) {
      return PluginFailureKind.NETWORK;
    }
    return PluginFailureKind.PLUGIN_BUG;
  }

  /**
   * Unwraps a wrapper throwable before classifying, so an {@code ExecutionException} around a
   * {@code TimeoutException} classifies as a timeout rather than a plugin bug.
   *
   * <p>Bounded to a few levels: a cyclic or pathologically deep cause chain is a plugin defect, and
   * following it without a bound turns a bad plugin into a host stack overflow.
   *
   * @param failure the throwable, which may be null
   * @return the classified kind
   */
  public static PluginFailureKind classifyUnwrapped(final Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; depth < MAX_CAUSE_DEPTH && current != null; depth++) {
      final PluginFailureKind kind = classify(current);
      if (kind != PluginFailureKind.PLUGIN_BUG) {
        return kind;
      }
      final Throwable cause = current.getCause();
      if (cause == current) {
        break;
      }
      current = cause;
    }
    return failure == null ? PluginFailureKind.UNKNOWN : PluginFailureKind.PLUGIN_BUG;
  }

  /** How far a cause chain is followed before it is treated as a plugin defect. */
  private static final int MAX_CAUSE_DEPTH = 8;
}
