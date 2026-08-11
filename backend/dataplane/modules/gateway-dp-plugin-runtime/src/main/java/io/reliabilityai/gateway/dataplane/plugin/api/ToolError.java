package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * A content-free description of why a tool call failed (Doc 28 §46).
 *
 * <p>The error names a <b>kind and a code</b>, never a value. A plugin's exception message is not
 * carried here and never reaches an audit record, a metric label or a caller: a third-party
 * plugin's message is arbitrary text that may contain anything it was processing, and forwarding it
 * is how customer content leaks into telemetry that is supposed to be content-free (Doc 14 §7.1).
 *
 * @param kind the classified failure kind
 * @param code a short, bounded, content-free code naming the specific fault
 * @param violation whether this failure was an attempted invariant breach
 */
public record ToolError(PluginFailureKind kind, String code, boolean violation)
    implements ContentFree {

  /** The longest code accepted, keeping metric labels bounded (Doc 14 §5). */
  public static final int MAX_CODE_LENGTH = 64;

  /** Compact constructor validating the content-free shape. */
  public ToolError {
    Preconditions.requireNonNull(kind, "kind");
    Preconditions.requireNonBlank(code, "code");
    if (code.length() > MAX_CODE_LENGTH) {
      throw new IllegalArgumentException("code exceeds " + MAX_CODE_LENGTH + " characters");
    }
  }

  /**
   * Creates an error from a kind, deriving the code and violation flag from the kind itself.
   *
   * @param kind the classified failure kind
   * @return the error
   */
  public static ToolError of(final PluginFailureKind kind) {
    Preconditions.requireNonNull(kind, "kind");
    return new ToolError(kind, kind.name().toLowerCase(java.util.Locale.ROOT), kind.violation());
  }

  /**
   * Creates an error with an explicit content-free code.
   *
   * @param kind the classified failure kind
   * @param code the content-free code
   * @return the error
   */
  public static ToolError of(final PluginFailureKind kind, final String code) {
    Preconditions.requireNonNull(kind, "kind");
    return new ToolError(kind, code, kind.violation());
  }
}
