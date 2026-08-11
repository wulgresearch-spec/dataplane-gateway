package io.reliabilityai.gateway.dataplane.observability.adapter;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.observability.api.RedactionPort;
import io.reliabilityai.gateway.dataplane.observability.api.RedactionVerdict;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Production {@link RedactionPort} — a deterministic, stateless, content-classification scanner
 * (Doc 27 §15.1, Doc 13 §20, Doc 14 §7.1). It is the reject-residual gate: if any attribute key or
 * value matches a known secret / credential / token / key-material / PII pattern, the whole signal
 * is {@link RedactionVerdict#REJECTED dropped} (fail-secure, Doc 27 PMR-7). It never mutates, never
 * logs, and never emits the offending value.
 *
 * <p><b>Design.</b> All detectors are precompiled {@link Pattern}s (immutable, thread-safe,
 * VT-safe), evaluated in a fixed order so the result is 100% deterministic (same input → same
 * verdict → same category) — replay-safe (Doc 32 §CRS). Credit-card candidates are Luhn-checked to
 * cut false positives. No allocation of per-call state beyond the JDK {@link Matcher}. Stateless.
 *
 * <p><b>Honest limits.</b> Pattern-based DLP is heuristic: it cannot detect an arbitrary novel
 * secret shape, and it may over-match (fail-secure) on a value that merely looks like a secret. It
 * is the industry-standard runtime content gate, not a proof of content-freedom; the primary
 * defense remains emitting only content-free value objects. High-entropy generic secrets without a
 * recognizable prefix are only caught via a {@code key=secret} assignment heuristic.
 */
public final class PatternRedactionScanner implements RedactionPort {

  /** The category a scanner match falls into (diagnostic; the port outcome is EMIT/REJECTED). */
  public enum Category {
    /** Email address. */
    EMAIL,
    /** US Social Security Number. */
    US_SSN,
    /** Luhn-valid credit-card / PAN. */
    CREDIT_CARD,
    /** Phone number (E.164 or US-formatted). */
    PHONE,
    /** A JSON Web Token. */
    JWT,
    /** An HTTP Bearer credential. */
    BEARER_TOKEN,
    /** An Anthropic API key ({@code sk-ant-…}). */
    ANTHROPIC_KEY,
    /** An OpenAI API key. */
    OPENAI_KEY,
    /** An AWS access-key id. */
    AWS_ACCESS_KEY,
    /** A Google/GCP API key. */
    GCP_API_KEY,
    /** A GitHub token. */
    GITHUB_TOKEN,
    /** A Slack token. */
    SLACK_TOKEN,
    /** A private-key PEM block. */
    PRIVATE_KEY_PEM,
    /** A {@code secret/password/api_key = <value>} assignment. */
    SECRET_ASSIGNMENT
  }

  private record Detector(Category category, Pattern pattern, boolean luhn) {}

  // Ordered most-specific → least-specific. Prefix-anchored key/token detectors precede the generic
  // assignment heuristic to attribute matches to the correct category.
  private static final List<Detector> DETECTORS =
      List.of(
          new Detector(
              Category.PRIVATE_KEY_PEM,
              Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"),
              false),
          new Detector(
              Category.JWT,
              Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
              false),
          new Detector(
              // Anthropic keys embed hyphens (sk-ant-api03-…); must precede the OpenAI detector,
              // which
              // stops at the first hyphen and would otherwise miss them entirely (primary provider
              // key).
              Category.ANTHROPIC_KEY, Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{16,}"), false),
          new Detector(
              Category.OPENAI_KEY, Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9]{20,}"), false),
          new Detector(
              Category.AWS_ACCESS_KEY,
              Pattern.compile("\\b(?:AKIA|ASIA|AGPA|AIDA|AROA|ANPA|ANVA|A3T)[A-Z0-9]{16}\\b"),
              false),
          new Detector(Category.GCP_API_KEY, Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), false),
          new Detector(
              Category.GITHUB_TOKEN,
              Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36}\\b"),
              false),
          new Detector(
              Category.SLACK_TOKEN, Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"), false),
          new Detector(
              Category.BEARER_TOKEN,
              Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]{8,}=*"),
              false),
          new Detector(
              Category.EMAIL,
              Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
              false),
          new Detector(Category.US_SSN, Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"), false),
          new Detector(Category.CREDIT_CARD, Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"), true),
          new Detector(
              Category.PHONE,
              Pattern.compile("(?:\\+[1-9]\\d{9,14}\\b)|(?:\\b\\d{3}[-.]\\d{3}[-.]\\d{4}\\b)"),
              false),
          new Detector(
              Category.SECRET_ASSIGNMENT,
              Pattern.compile(
                  "(?i)\\b(?:api[_-]?key|secret|password|passwd|access[_-]?token|auth[_-]?token"
                      + "|client[_-]?secret|private[_-]?key)\\b\\s*[:=]\\s*['\"]?\\S{6,}"),
              false));

  @Override
  public RedactionVerdict scan(final Map<String, String> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return RedactionVerdict.EMIT;
    }
    for (final Map.Entry<String, String> entry : attributes.entrySet()) {
      if (detect(entry.getKey()).isPresent() || detect(entry.getValue()).isPresent()) {
        return RedactionVerdict.REJECTED;
      }
    }
    return RedactionVerdict.EMIT;
  }

  /**
   * Classifies a single string, returning the first matching sensitive category (Doc 27 §15.1).
   * Pure and deterministic; used by {@link #scan} and directly by tests.
   *
   * @param text the text to classify (nullable)
   * @return the matched category, or empty if the text contains no recognized sensitive pattern
   */
  public Optional<Category> detect(final String text) {
    if (text == null || text.isEmpty()) {
      return Optional.empty();
    }
    for (final Detector detector : DETECTORS) {
      final Matcher matcher = detector.pattern().matcher(text);
      while (matcher.find()) {
        if (!detector.luhn()) {
          return Optional.of(detector.category());
        }
        if (luhnValid(matcher.group())) {
          return Optional.of(detector.category());
        }
      }
    }
    return Optional.empty();
  }

  private static boolean luhnValid(final String candidate) {
    int sum = 0;
    int digits = 0;
    boolean alternate = false;
    for (int i = candidate.length() - 1; i >= 0; i--) {
      final char c = candidate.charAt(i);
      if (c < '0' || c > '9') {
        continue;
      }
      digits++;
      int d = c - '0';
      if (alternate) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      alternate = !alternate;
    }
    return digits >= 13 && digits <= 19 && sum % 10 == 0;
  }

  /**
   * A stable, lower-cased category name for a detected violation (content-free — the category,
   * never the value), suitable for a diagnostic label.
   *
   * @param category the category
   * @return a stable lower-case label
   */
  public static String label(final Category category) {
    return Preconditions.requireNonNull(category, "category").name().toLowerCase(Locale.ROOT);
  }
}
