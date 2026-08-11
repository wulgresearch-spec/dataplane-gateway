package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Thrown when an authored policy bundle cannot be compiled into a usable snapshot.
 *
 * <p>The only exception this module throws, and it is thrown exclusively at <b>load time</b> —
 * never during evaluation, where a throw would be a code path that does not end in a decision.
 * Refusing a bad bundle loudly at load, while the previous good snapshot keeps serving, is strictly
 * better than installing it and discovering the problem one denied customer request at a time.
 *
 * <p>The message names the offending rule by its control-plane id so an operator can find it, and
 * carries no policy contents.
 */
public final class PolicyCompilationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was wrong with the bundle
   */
  public PolicyCompilationException(final String message) {
    super(Preconditions.requireNonBlank(message, "message"));
  }
}
