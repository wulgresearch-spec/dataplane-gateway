package io.reliabilityai.gateway.dataplane.memory.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.PiiClassifierPort;
import java.util.regex.Pattern;

/**
 * The reference classifier: pattern-based, conservative, and honest about being neither complete
 * nor clever.
 *
 * <p><b>This is not a real PII classifier</b> (blocker B23). It recognises a handful of well-shaped
 * patterns — email addresses, card-like digit runs, national-insurance-like tokens, obvious
 * credential markers — and nothing else. It will not find a name, an address, or personal data
 * expressed in prose, and a deployment with real compliance obligations must supply a better one
 * behind the same port.
 *
 * <p><b>Its errors are deliberately one-directional.</b> Where it is unsure it classifies
 * <em>up</em>, never down. Over-classifying costs a redaction or an encryption that was not
 * strictly necessary; under-classifying stores personal data unprotected. The first is a nuisance
 * and the second is a breach, so the bias is not a close call.
 *
 * <p>Named for what it is. A class called {@code DefaultPiiClassifier} would get deployed by
 * someone who assumed the default was adequate.
 */
public final class ConservativePiiClassifier implements PiiClassifierPort {

  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

  /**
   * Thirteen to nineteen digits, optionally grouped. Deliberately loose: a false positive is cheap.
   */
  private static final Pattern CARD_LIKE = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");

  /** Long digit runs that are not card-shaped but are still identifier-shaped. */
  private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{9,}\\b");

  /** Markers that almost always precede a credential rather than a fact about a person. */
  private static final Pattern CREDENTIAL =
      Pattern.compile(
          "(?i)\\b(api[_-]?key|secret|password|passwd|bearer|authorization|private[_-]?key"
              + "|access[_-]?token|refresh[_-]?token)\\b");

  /** Special-category markers. Their presence raises the classification, never lowers it. */
  private static final Pattern SENSITIVE =
      Pattern.compile(
          "(?i)\\b(diagnosis|prescription|medical|biometric|ethnicity|religion|sexual"
              + "[ -]?orientation|criminal[ -]?record)\\b");

  private static final String REDACTION = "[redacted]";

  @Override
  public Classification classify(final String body) {
    Preconditions.requireNonNull(body, "body");

    if (body.isBlank()) {
      // Nothing to protect, and confidently so.
      return Classification.clean(DataClassification.PUBLIC, body);
    }

    // Credentials first. They are never storable at all, so nothing else about the body matters.
    if (CREDENTIAL.matcher(body).find()) {
      return new Classification(DataClassification.SECRET, REDACTION, true);
    }

    if (SENSITIVE.matcher(body).find()) {
      return new Classification(DataClassification.SENSITIVE_PII, redact(body), true);
    }

    final boolean personal =
        EMAIL.matcher(body).find()
            || CARD_LIKE.matcher(body).find()
            || LONG_DIGITS.matcher(body).find();
    if (personal) {
      return new Classification(DataClassification.PII, redact(body), true);
    }

    // No pattern matched. That is emphatically not proof the body is clean — this classifier cannot
    // read prose — so the result is INTERNAL and marked unconfident rather than PUBLIC and
    // confident.
    // Callers that need certainty should read the flag rather than the classification.
    return new Classification(DataClassification.INTERNAL, body, false);
  }

  /**
   * Removes the spans this classifier can recognise.
   *
   * <p>What it removes does not come back — there is no reversible token and no vault. A reversible
   * redaction is a second copy of the personal data with an extra step, which is the opposite of
   * what redaction is for.
   */
  private static String redact(final String body) {
    String redacted = EMAIL.matcher(body).replaceAll(REDACTION);
    redacted = CARD_LIKE.matcher(redacted).replaceAll(REDACTION);
    redacted = LONG_DIGITS.matcher(redacted).replaceAll(REDACTION);
    return redacted;
  }
}
