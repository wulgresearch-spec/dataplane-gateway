package io.reliabilityai.gateway.dataplane.memory.api;

/**
 * How a query finds records (AD-026 §7.1).
 *
 * <p>Metadata, time and scope filters are <em>not</em> alternatives to keyword and semantic — they
 * compose with any mode. They appear here as modes for the case where a filter is the whole query.
 *
 * <p>Every filter is pushed down to the adapter. The runtime never retrieves broadly and narrows in
 * memory, because a broad retrieval has already read the data it was meant to exclude.
 */
public enum RetrievalMode {

  /** Lexical match against the query text. */
  KEYWORD(true, false),

  /** Similarity against an embedding of the query text, or a caller-supplied vector. */
  SEMANTIC(false, true),

  /** Both, fused by reciprocal rank (AD-026 §7.3). */
  HYBRID(true, true),

  /** Metadata predicates alone. */
  METADATA(false, false),

  /** A time window alone. */
  TIME(false, false),

  /** Scope alone: everything of these types that the caller may see. */
  SCOPE(false, false);

  private final boolean needsText;
  private final boolean needsVector;

  RetrievalMode(final boolean needsText, final boolean needsVector) {
    this.needsText = needsText;
    this.needsVector = needsVector;
  }

  /**
   * Reports whether this mode requires query text.
   *
   * @return true for {@link #KEYWORD} and {@link #HYBRID}
   */
  public boolean needsText() {
    return needsText;
  }

  /**
   * Reports whether this mode requires a vector, whether supplied or embedded from text.
   *
   * @return true for {@link #SEMANTIC} and {@link #HYBRID}
   */
  public boolean needsVector() {
    return needsVector;
  }

  /**
   * Reports whether this mode can still be served when the vector path is unavailable.
   *
   * <p>{@link #HYBRID} degrades to keyword and says so in the result (AD-026 §11); {@link
   * #SEMANTIC} does not, because degrading it silently would answer a different question than the
   * one asked.
   *
   * @return true when a keyword-only fallback is honest for this mode
   */
  public boolean degradesToKeyword() {
    return this == HYBRID;
  }
}
