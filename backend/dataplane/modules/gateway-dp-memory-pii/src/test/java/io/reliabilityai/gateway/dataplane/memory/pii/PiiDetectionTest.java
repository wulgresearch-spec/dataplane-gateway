package io.reliabilityai.gateway.dataplane.memory.pii;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** What the built-in rules find, and — more importantly — what they refuse to find. */
@DisplayName("built-in detection")
final class PiiDetectionTest {

  private InProcessPiiMetrics metrics;
  private PiiDetectionEngine engine;

  @BeforeEach
  void setUp() {
    metrics = new InProcessPiiMetrics();
    engine = new PiiDetectionEngine(PiiRuleCompiler.builtIn(), metrics);
  }

  @ParameterizedTest(name = "[{index}] {1} is found in \"{0}\"")
  @CsvSource(
      delimiter = '|',
      value = {
        "contact alice.smith@example.com now          | EMAIL",
        "reach me on postmaster@sub.domain.co.uk      | EMAIL",
        "call 555-123-4567 today                      | PHONE",
        "phone: +91 98765 43210                       | PHONE",
        "mobile 9876543210 is mine                    | PHONE",
        "card 4111 1111 1111 1111 charged             | CREDIT_CARD",
        "card 5500005555555559 declined               | CREDIT_CARD",
        "IBAN GB82 WEST 1234 5698 7654 32 settled     | BANK_ACCOUNT",
        "account number: 123456789012                 | BANK_ACCOUNT",
        "pay alice@okhdfcbank today                   | UPI_ID",
        "aadhaar 2345 6789 0124 verified              | NATIONAL_ID",
        "ssn 123-45-6789 on file                      | NATIONAL_ID",
        "pan ABCDE1234F filed                         | TAX_ID",
        "passport J8369854 issued                     | PASSPORT_NUMBER",
        "driving licence no: DL0420110149646          | DRIVER_LICENSE",
        "server 192.168.1.254 responded               | IP_ADDRESS",
        "host 2001:0db8:85a3:0000:0000:8a2e:0370:7334 | IP_ADDRESS",
        "nic 00:1A:2B:3C:4D:5E up                     | MAC_ADDRESS",
        "location 37.774929, -122.419418 pinned       | GPS_COORDINATE",
        "dob 1985-03-21 recorded                      | DATE_OF_BIRTH",
        "date of birth 21/03/1985 confirmed           | DATE_OF_BIRTH",
        "MRN: A9928311 in chart                       | MEDICAL_RECORD_NUMBER",
        "patient id: PT-99321 admitted                | PATIENT_ID",
        "policy number: HX-99213345 active            | INSURANCE_NUMBER",
        "case no: CV-2024-1188 filed                  | LEGAL_CASE_REFERENCE",
        "employee id: E-4471 onboarded                | EMPLOYEE_ID",
        "customer id: C-99213 renewed                 | CUSTOMER_ID",
        "password = hunter2xyz                        | CREDENTIAL",
        "api_key: sk_live_abcdef0123456789ABCD        | SECRET_KEY",
        "12 Baker Street, London                      | POSTAL_ADDRESS",
        "Dr. Jane Watson attended                     | NAME",
      })
  @DisplayName("every supported type is detected in a labelled example")
  void everySupportedTypeIsDetectedInALabelledExample(final String body, final PiiType expected) {
    assertThat(engine.detect(body).types()).contains(expected);
  }

  @ParameterizedTest(name = "[{index}] nothing is found in \"{0}\"")
  @ValueSource(
      strings = {
        "the quick brown fox jumps over the lazy dog",
        "deployed build 2024-01-15 successfully to production",
        "order number 1234567890123456 shipped yesterday",
        "card 4111 1111 1111 1112 was declined",
        "grouped digits 2345 6789 0123 with no label",
        "version 999.888.777.666 released",
        "the total came to 1234.56 after tax",
        "error rate rose to 0.0001 percent overnight",
        "commit 8f2a4c1b9e7d3f6a0c5b2e8d1a4f7c3b6e9d2a5f landed",
        "see section 12.4.5.6 of the handbook",
      })
  @DisplayName("ordinary text produces no detections")
  void ordinaryTextProducesNoDetections(final String body) {
    final PiiDetection detection = engine.detect(body);

    assertThat(detection.any()).as("spans: %s", detection.spans()).isFalse();
    assertThat(detection.severity()).isEqualTo(PiiSeverity.NONE);
  }

  @Test
  @DisplayName("a card number that fails Luhn is not reported at all")
  void aCardNumberThatFailsLuhnIsNotReportedAtAll() {
    final PiiDetection detection = engine.detect("card 4111 1111 1111 1112 on file");

    // This is the single most valuable thing the validators do. A bare thirteen-to-nineteen digit
    // pattern matches order numbers, timestamps and telemetry; Luhn removes them without removing a
    // single genuine card. It must also not fall through to some looser rule — an earlier version
    // of
    // these rules reported exactly this string as a phone number.
    assertThat(detection.any()).isFalse();
    assertThat(metrics.count("validatorRejected.CREDIT_CARD")).isEqualTo(1);
  }

  @Test
  @DisplayName("an Aadhaar number that fails Verhoeff is not reported at all")
  void anAadhaarNumberThatFailsVerhoeffIsNotReportedAtAll() {
    assertThat(engine.detect("aadhaar 2345 6789 0123 verified").any()).isFalse();
    assertThat(engine.detect("aadhaar 2345 6789 0124 verified").types())
        .containsExactly(PiiType.NATIONAL_ID);
  }

  @Test
  @DisplayName("an IBAN that fails mod-97 is not reported")
  void anIbanThatFailsModNinetySevenIsNotReported() {
    assertThat(engine.detect("IBAN GB82 WEST 1234 5698 7654 33 settled").types())
        .doesNotContain(PiiType.BANK_ACCOUNT);
  }

  @Test
  @DisplayName("a dotted quad outside 0-255 is not an address")
  void aDottedQuadOutsideTheOctetRangeIsNotAnAddress() {
    assertThat(engine.detect("version 999.888.777.666 released").any()).isFalse();
    assertThat(engine.detect("server 192.168.1.254 responded").types())
        .containsExactly(PiiType.IP_ADDRESS);
  }

  @Test
  @DisplayName("coordinates outside the valid range are not locations")
  void coordinatesOutsideTheValidRangeAreNotLocations() {
    assertThat(engine.detect("ratio 91.500000, 200.000000 computed").any()).isFalse();
    assertThat(engine.detect("gps 0.000000, 0.000000 placeholder").any()).isFalse();
  }

  @Test
  @DisplayName("a bare date is ignored but a labelled one is a date of birth")
  void aBareDateIsIgnoredButALabelledOneIsADateOfBirth() {
    // The whole point of confidence scoring. Timestamps outnumber birth dates by orders of
    // magnitude
    // in any real corpus, and a detector that flags every ISO date is one that gets switched off.
    assertThat(engine.detect("deployed on 1985-03-21 successfully").any()).isFalse();
    assertThat(engine.detect("dob 1985-03-21 recorded").types())
        .containsExactly(PiiType.DATE_OF_BIRTH);
    assertThat(metrics.count("droppedBelowConfidence.DATE_OF_BIRTH")).isEqualTo(1);
  }

  @Test
  @DisplayName("a passport-shaped token needs its label to be reported")
  void aPassportShapedTokenNeedsItsLabelToBeReported() {
    assertThat(engine.detect("token J8369854 generated").any()).isFalse();
    assertThat(engine.detect("passport J8369854 issued").types())
        .containsExactly(PiiType.PASSPORT_NUMBER);
  }

  @Test
  @DisplayName("mixed content reports every entity and the worst severity")
  void mixedContentReportsEveryEntityAndTheWorstSeverity() {
    final PiiDetection detection =
        engine.detect(
            "Contact alice@example.com or call 555-123-4567. "
                + "Card 4111 1111 1111 1111 on file. Server 10.0.0.7.");

    assertThat(detection.types())
        .containsExactlyInAnyOrder(
            PiiType.EMAIL, PiiType.PHONE, PiiType.CREDIT_CARD, PiiType.IP_ADDRESS);
    // Maximum, not average: padding sensitive content with harmless text must not dilute it.
    assertThat(detection.severity()).isEqualTo(PiiSeverity.CRITICAL);
    assertThat(detection.summaryCategory()).contains(PiiCategory.MULTIPLE);
  }

  @Test
  @DisplayName("a single category does not summarise as MULTIPLE")
  void aSingleCategoryDoesNotSummariseAsMultiple() {
    final PiiDetection detection = engine.detect("alice@example.com and bob@example.com");

    assertThat(detection.spans()).hasSize(2);
    assertThat(detection.summaryCategory()).contains(PiiCategory.PERSONAL);
  }

  @Test
  @DisplayName("repeated entities are all reported, in document order")
  void repeatedEntitiesAreAllReportedInDocumentOrder() {
    final PiiDetection detection = engine.detect("a@x.com then b@y.com then c@z.com then d@w.com");

    assertThat(detection.spans()).hasSize(4);
    assertThat(detection.countsByType()).containsEntry(PiiType.EMAIL, 4);
    for (int i = 1; i < detection.spans().size(); i++) {
      assertThat(detection.spans().get(i).start())
          .isGreaterThanOrEqualTo(detection.spans().get(i - 1).end());
    }
  }

  @Test
  @DisplayName("spans never overlap")
  void spansNeverOverlap() {
    final PiiDetection detection =
        engine.detect(
            "patient id: PT-99321, MRN: A9928311, policy number: HX-99213345, "
                + "email dr.who@example.com, card 4111 1111 1111 1111");

    final List<PiiSpan> spans = detection.spans();
    for (int i = 0; i < spans.size(); i++) {
      for (int j = i + 1; j < spans.size(); j++) {
        assertThat(spans.get(i).overlaps(spans.get(j)))
            .as("%s overlaps %s", spans.get(i), spans.get(j))
            .isFalse();
      }
    }
  }

  @Test
  @DisplayName("spans carry offsets and never the matched text")
  void spansCarryOffsetsAndNeverTheMatchedText() {
    final String body = "contact alice.smith@example.com now";
    final PiiSpan span = engine.detect(body).spans().get(0);

    assertThat(body.substring(span.start(), span.end())).isEqualTo("alice.smith@example.com");
    // A detection result is something you log, audit and count. If it quoted the match, it would be
    // a second copy of the data with fewer protections than the first.
    assertThat(span.toString()).doesNotContain("alice.smith@example.com");
  }

  @Test
  @DisplayName("unicode content is scanned and offsets stay correct")
  void unicodeContentIsScannedAndOffsetsStayCorrect() {
    final String body = "連絡先は alice@example.com です — naïve café 🔐 ok";
    final PiiDetection detection = engine.detect(body);

    assertThat(detection.types()).containsExactly(PiiType.EMAIL);
    final PiiSpan span = detection.spans().get(0);
    assertThat(body.substring(span.start(), span.end())).isEqualTo("alice@example.com");
  }

  @Test
  @DisplayName("an emoji-heavy body with no PII produces nothing")
  void anEmojiHeavyBodyWithNoPiiProducesNothing() {
    assertThat(engine.detect("🎉🎊✨ shipping today ✨🎊🎉 🚀🚀🚀").any()).isFalse();
  }

  @Test
  @DisplayName("PII nested inside JSON is found")
  void piiNestedInsideJsonIsFound() {
    final String json =
        "{\"user\":{\"profile\":{\"email\":\"alice@example.com\",\"contact\":"
            + "{\"phone\":\"555-123-4567\"}},\"payment\":{\"card\":\"4111111111111111\"}}}";

    // The engine is structure-blind by design: it scans text. That is a strength here — no parser
    // to
    // be defeated by an unexpected encoding — and a weakness recorded in AD-029 §10.3, because a
    // value split across JSON string concatenation would be missed.
    assertThat(engine.detect(json).types())
        .containsExactlyInAnyOrder(PiiType.EMAIL, PiiType.PHONE, PiiType.CREDIT_CARD);
  }

  @Test
  @DisplayName("detection is deterministic across repeated runs")
  void detectionIsDeterministicAcrossRepeatedRuns() {
    final String body =
        "alice@example.com, 555-123-4567, 4111 1111 1111 1111, 10.0.0.7, dob 1985-03-21";
    final PiiDetection first = engine.detect(body);

    for (int i = 0; i < 200; i++) {
      final PiiDetection again = engine.detect(body);
      assertThat(again.spans()).isEqualTo(first.spans());
      assertThat(again.severity()).isEqualTo(first.severity());
      assertThat(again.categories()).isEqualTo(first.categories());
    }
  }

  @Test
  @DisplayName("an empty or null body is clean rather than an error")
  void anEmptyOrNullBodyIsCleanRatherThanAnError() {
    assertThat(engine.detect(null).any()).isFalse();
    assertThat(engine.detect("").any()).isFalse();
    assertThat(engine.detect("").complete()).isTrue();
  }

  @Test
  @DisplayName("the result names the rule set that produced it")
  void theResultNamesTheRuleSetThatProducedIt() {
    final PiiRuleSet rules = PiiRuleCompiler.builtIn();
    final PiiDetection detection =
        new PiiDetectionEngine(rules, PiiMetricsPort.NOOP).detect("alice@example.com");

    // Without this, re-classifying stored records after a rule change is guesswork.
    assertThat(detection.ruleSetVersion()).isEqualTo(rules.version());
    assertThat(detection.spans().get(0).ruleId()).isEqualTo("builtin.email");
    assertThat(detection.spans().get(0).ruleVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName("no built-in rule declares the MULTIPLE summary category")
  void noBuiltInRuleDeclaresTheMultipleSummaryCategory() {
    assertThat(BuiltInRules.all())
        .allSatisfy(rule -> assertThat(rule.category()).isNotEqualTo(PiiCategory.MULTIPLE));
  }

  @Test
  @DisplayName("every built-in rule identifier is unique")
  void everyBuiltInRuleIdentifierIsUnique() {
    assertThat(BuiltInRules.all().stream().map(PiiRule::id).distinct().count())
        .isEqualTo(BuiltInRules.all().size());
  }
}
