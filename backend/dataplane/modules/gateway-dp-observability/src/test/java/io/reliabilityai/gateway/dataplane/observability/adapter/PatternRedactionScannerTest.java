package io.reliabilityai.gateway.dataplane.observability.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.observability.adapter.PatternRedactionScanner.Category;
import io.reliabilityai.gateway.dataplane.observability.api.RedactionVerdict;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Detection + determinism tests for the production redaction scanner (Doc 27 §15.1). */
class PatternRedactionScannerTest {

  private final PatternRedactionScanner scanner = new PatternRedactionScanner();

  @Test
  void detectsSecretsCredentialsKeysAndPii() {
    assertThat(scanner.detect("user@example.com")).contains(Category.EMAIL);
    assertThat(scanner.detect("SSN 123-45-6789")).contains(Category.US_SSN);
    assertThat(scanner.detect("4111111111111111"))
        .contains(Category.CREDIT_CARD); // Luhn-valid Visa test
    assertThat(scanner.detect("call +14155552671")).contains(Category.PHONE);
    assertThat(scanner.detect("call 415-555-2671")).contains(Category.PHONE);
    assertThat(scanner.detect("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhYmMifQ.c2lnbmF0dXJlLXZhbHVl"))
        .contains(Category.JWT);
    assertThat(scanner.detect("Authorization: Bearer abcDEF123.token-value"))
        .contains(Category.BEARER_TOKEN);
    assertThat(scanner.detect("sk-abcdefghijklmnopqrstuvwx")).contains(Category.OPENAI_KEY);
    // Anthropic key (embedded hyphens) — previously missed by the OpenAI pattern (primary
    // provider).
    assertThat(scanner.detect("sk-ant-api03-AbCdEf012345_6789-ghIJKLmnop"))
        .contains(Category.ANTHROPIC_KEY);
    assertThat(scanner.detect("AKIAIOSFODNN7EXAMPLE")).contains(Category.AWS_ACCESS_KEY);
    assertThat(scanner.detect("AIza" + "A".repeat(35))).contains(Category.GCP_API_KEY);
    assertThat(scanner.detect("ghp_0123456789abcdefghijklmnopqrstuvwxyz"))
        .contains(Category.GITHUB_TOKEN);
    assertThat(scanner.detect("xoxb-1234567890-abcdefg")).contains(Category.SLACK_TOKEN);
    assertThat(scanner.detect("-----BEGIN RSA PRIVATE KEY-----"))
        .contains(Category.PRIVATE_KEY_PEM);
    assertThat(scanner.detect("password = hunter2secret")).contains(Category.SECRET_ASSIGNMENT);
    assertThat(scanner.detect("client_secret: s3cr3t-value")).contains(Category.SECRET_ASSIGNMENT);
  }

  @Test
  void doesNotFlagBenignOperationalValues() {
    for (final String benign :
        new String[] {
          "outcome=ok",
          "region=us-east-1",
          "latency_ms=42",
          "count=1000",
          "adapter.invoke",
          "http_status=200",
          "tenant-scope-hash",
          "v7",
          "authenticated",
          "corr-1",
          "credential-near-expiry",
          "model=canonical-m1"
        }) {
      assertThat(scanner.detect(benign)).as("benign: %s", benign).isEmpty();
    }
  }

  @Test
  void invalidCreditCardNumberIsNotFlagged() {
    // Fails Luhn → not a credit card (reduces false positives).
    assertThat(scanner.detect("4111111111111112")).isEmpty();
    assertThat(scanner.detect("1234567890123")).isEmpty();
  }

  @Test
  void detectionIsDeterministic() {
    final String sample = "contact user@example.com key sk-abcdefghijklmnopqrstuvwx";
    final var first = scanner.detect(sample);
    for (int i = 0; i < 100; i++) {
      assertThat(scanner.detect(sample)).isEqualTo(first);
    }
  }

  @Test
  void scanRejectsWhenAnyAttributeIsSensitive() {
    assertThat(scanner.scan(Map.of("outcome", "ok", "region", "us-east-1")))
        .isEqualTo(RedactionVerdict.EMIT);
    assertThat(scanner.scan(Map.of("outcome", "ok", "note", "email user@example.com")))
        .isEqualTo(RedactionVerdict.REJECTED);
    assertThat(scanner.scan(Map.of("Authorization", "Bearer abcDEF123.tok")))
        .isEqualTo(RedactionVerdict.REJECTED);
  }

  @Test
  void nullAndEmptyAreEmitAndDetectEmpty() {
    assertThat(scanner.scan(null)).isEqualTo(RedactionVerdict.EMIT);
    assertThat(scanner.scan(Map.of())).isEqualTo(RedactionVerdict.EMIT);
    assertThat(scanner.detect(null)).isEmpty();
    assertThat(scanner.detect("")).isEmpty();
  }

  @Test
  void fuzzBenignShortTokensDoNotOverMatch() {
    // Deterministic pseudo-fuzz (no RNG): structured benign tokens must not trip the scanner.
    int flagged = 0;
    for (int i = 0; i < 2000; i++) {
      final String candidate = "attr" + i + "_value" + (i * 31 % 97);
      if (scanner.detect(candidate).isPresent()) {
        flagged++;
      }
    }
    assertThat(flagged).isZero();
  }
}
