package io.reliabilityai.gateway.dataplane.embedding.api;

/**
 * Why one input did not become an embedding (AD-030 §9).
 *
 * <p>Neutral categories, not provider codes. A caller deciding whether to fail a write, degrade to
 * keyword-only retrieval, or retry later needs to know the <em>kind</em> of failure; a vendor error
 * string forces every caller to learn one vendor's vocabulary and re-learn it for the next.
 *
 * <p>The {@code retryable} flag is advisory and is what {@code EmbeddingRetryPolicy} acts on. It
 * follows the same classification the rest of the gateway uses for provider faults, so embedding
 * retries behave like every other retry in the system rather than like a second, subtly different
 * policy nobody remembers exists.
 */
public enum EmbeddingFailure {

  /** The provider asked us to slow down. */
  RATE_LIMITED(true),

  /** No answer within the attempt budget. */
  TIMEOUT(true),

  /** The provider is down, restarting, or refusing traffic. */
  UNAVAILABLE(true),

  /** The connection failed or was reset mid-flight. */
  NETWORK(true),

  /**
   * The provider rejected the request as malformed or unsupported. Retrying repeats the mistake.
   */
  REJECTED(false),

  /** Credentials are missing, wrong, or not permitted. Retrying is an authentication attack. */
  AUTH_FAILED(false),

  /** The input exceeds what the model accepts, and no retry makes it shorter. */
  TOO_LARGE(false),

  /** The provider returned a vector of the wrong width for the model it was asked for. */
  DIMENSION_MISMATCH(false),

  /** Governance refused the spend before the call was made. */
  BUDGET_EXCEEDED(false),

  /** Anything else, which is treated as non-retryable because its cause is unknown. */
  INTERNAL(false);

  private final boolean retryable;

  /**
   * Creates a failure kind.
   *
   * @param retryable whether repeating the call could plausibly succeed
   */
  EmbeddingFailure(final boolean retryable) {
    this.retryable = retryable;
  }

  /**
   * Whether repeating the call could plausibly succeed.
   *
   * @return true for transient conditions
   */
  public boolean retryable() {
    return retryable;
  }
}
