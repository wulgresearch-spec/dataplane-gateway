package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.nio.charset.StandardCharsets;

/**
 * Minimum structural JSON scanning (Doc 18 §20.1, RB-4) — tracks <b>brace/bracket depth and
 * string/escape state</b> only, to confirm a byte stream is <em>syntactically progressing as
 * well-formed JSON</em> and to delimit element boundaries. It is <b>structurally aware but
 * semantically blind</b>: it never interprets a value, validates a schema, assembles a logical
 * object, or performs business validation — all four are exclusively SchemaLock's (Doc 18
 * §20.1/§43.1-SG, SG-A18). Pure and deterministic (Doc 18 §34).
 */
public final class JsonStructuralScanner {

  private int depth;
  private boolean inString;
  private boolean escaped;
  private boolean sawToken;
  private boolean structurallyBroken;
  private boolean topLevelComplete;

  /**
   * Feeds one byte, updating depth/string/escape state (Doc 18 §20.1).
   *
   * @param b the next byte
   */
  public void push(final byte b) {
    if (structurallyBroken) {
      return;
    }
    final char c = (char) (b & 0xFF);
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '"') {
        inString = false;
      }
      return;
    }
    final boolean whitespace = c == ' ' || c == '\t' || c == '\r' || c == '\n';
    if (topLevelComplete && !whitespace) {
      structurallyBroken = true; // trailing content after a complete top-level value (RB-4, §20.1)
      return;
    }
    switch (c) {
      case '"' -> {
        inString = true;
        sawToken = true;
      }
      case '{', '[' -> {
        depth++;
        sawToken = true;
      }
      case '}', ']' -> {
        depth--;
        sawToken = true;
        if (depth < 0) {
          structurallyBroken = true; // unbalanced close
        } else if (depth == 0) {
          topLevelComplete = true; // a top-level container closed; no further tokens permitted
        }
      }
      case ' ', '\t', '\r', '\n' -> {
        // insignificant whitespace between tokens
      }
      default -> sawToken = true; // number/true/false/null token bytes
    }
  }

  /**
   * The current nesting depth (0 at top level).
   *
   * @return the depth
   */
  public int depth() {
    return depth;
  }

  /**
   * Whether the scanner is currently inside a JSON string literal.
   *
   * @return {@code true} if inside a string
   */
  public boolean inString() {
    return inString;
  }

  /**
   * Whether the scanned bytes form a complete, balanced, structurally well-formed JSON fragment: at
   * least one token, depth returned to zero, not mid-string, and no unbalanced close (Doc 18
   * §20.1).
   *
   * @return {@code true} if a complete well-formed fragment
   */
  public boolean isCompleteWellFormed() {
    return sawToken && !structurallyBroken && depth == 0 && !inString;
  }

  /**
   * Validates a complete JSON fragment structurally (Doc 18 §20.1) — no semantics, no schema.
   *
   * @param fragment the candidate fragment bytes
   * @return {@code true} iff structurally complete and well-formed
   */
  public static boolean isCompleteWellFormed(final byte[] fragment) {
    final JsonStructuralScanner scanner = new JsonStructuralScanner();
    for (final byte b : fragment) {
      scanner.push(b);
    }
    return scanner.isCompleteWellFormed();
  }

  /**
   * Renders the well-known SSE/NDJSON terminal sentinel bytes for comparison ({@code [DONE]}).
   *
   * @return the terminal sentinel bytes
   */
  public static byte[] doneSentinel() {
    return "[DONE]".getBytes(StandardCharsets.US_ASCII);
  }
}
