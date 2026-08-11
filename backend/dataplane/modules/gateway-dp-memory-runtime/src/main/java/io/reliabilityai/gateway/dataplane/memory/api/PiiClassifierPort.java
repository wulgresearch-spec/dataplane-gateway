package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Establishes what kind of data a body contains (AD-026 §5.1).
 *
 * <p>A port, because classification is a judgement that improves over time and that different
 * deployments make differently — a regulated tenant wants a stricter classifier than a hobby
 * project, and neither should require a change to the memory plane to get one.
 *
 * <p><b>The caller never classifies its own content.</b> A caller that could declare its content
 * non-personal would have opted out of every PII rule by saying so, which is not a rule.
 */
public interface PiiClassifierPort {

  /**
   * What a classifier determined.
   *
   * @param classification the established classification
   * @param redactedBody the body with personal spans removed, where the classifier can produce one
   * @param confident whether the classifier is sure of its answer
   */
  record Classification(DataClassification classification, String redactedBody, boolean confident) {

    /**
     * Validates the result.
     *
     * @param classification the established classification
     * @param redactedBody the redacted body
     * @param confident whether the classifier is sure
     */
    public Classification {
      Preconditions.requireNonNull(classification, "classification");
      Preconditions.requireNonNull(redactedBody, "redactedBody");
    }

    /**
     * A confident classification that required no redaction.
     *
     * @param classification the established classification
     * @param body the unchanged body
     * @return the result
     */
    public static Classification clean(final DataClassification classification, final String body) {
      return new Classification(classification, body, true);
    }
  }

  /**
   * Classifies a body.
   *
   * <p>An implementation that cannot decide must return {@link DataClassification#UNCLASSIFIED}
   * rather than guessing {@code PUBLIC}. Unclassified fails closed (MEM-21); a guess of public
   * fails open, and fails open silently, which is the worst combination available.
   *
   * @param body the plaintext to classify
   * @return what it found
   */
  Classification classify(String body);
}
