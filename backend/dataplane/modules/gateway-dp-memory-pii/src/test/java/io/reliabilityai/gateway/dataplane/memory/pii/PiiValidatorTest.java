package io.reliabilityai.gateway.dataplane.memory.pii;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The checksum and range checks, which are what separate this from a list of regexes. */
@DisplayName("validators")
final class PiiValidatorTest {

  @ParameterizedTest(name = "{0} passes Luhn")
  @ValueSource(
      strings = {
        "4111111111111111",
        "4111 1111 1111 1111",
        "4111-1111-1111-1111",
        "5500005555555559",
        "378282246310005",
        "6011111111111117",
        "3530111333300000"
      })
  @DisplayName("genuine card numbers pass Luhn")
  void genuineCardNumbersPassLuhn(final String candidate) {
    assertThat(PiiValidator.LUHN.accepts(candidate)).isTrue();
  }

  @ParameterizedTest(name = "{0} fails Luhn")
  @ValueSource(
      strings = {
        "4111111111111112",
        "1234567890123456",
        "0000000000000001",
        "9999999999999999",
        "4111111111111",
        "123456789012"
      })
  @DisplayName("numbers that are not cards fail Luhn")
  void numbersThatAreNotCardsFailLuhn(final String candidate) {
    assertThat(PiiValidator.LUHN.accepts(candidate)).isFalse();
  }

  @Test
  @DisplayName("Luhn rejects everything outside the card length range")
  void luhnRejectsEverythingOutsideTheCardLengthRange() {
    assertThat(PiiValidator.LUHN.accepts("18")).isFalse();
    assertThat(PiiValidator.LUHN.accepts("1".repeat(25))).isFalse();
  }

  @ParameterizedTest(name = "{0} passes mod-97")
  @ValueSource(
      strings = {
        "GB82WEST12345698765432",
        "GB82 WEST 1234 5698 7654 32",
        "DE89370400440532013000",
        "FR1420041010050500013M02606"
      })
  @DisplayName("genuine IBANs pass mod-97")
  void genuineIbansPassModNinetySeven(final String candidate) {
    assertThat(PiiValidator.IBAN_MOD97.accepts(candidate)).isTrue();
  }

  @ParameterizedTest(name = "{0} fails mod-97")
  @ValueSource(
      strings = {"GB82WEST12345698765433", "DE89370400440532013001", "ZZ00NOTANIBANATALL01"})
  @DisplayName("altered IBANs fail mod-97")
  void alteredIbansFailModNinetySeven(final String candidate) {
    assertThat(PiiValidator.IBAN_MOD97.accepts(candidate)).isFalse();
  }

  @ParameterizedTest(name = "{0} passes Verhoeff")
  @ValueSource(strings = {"234567890124", "2345 6789 0124", "345678901238", "987654321012"})
  @DisplayName("Verhoeff-valid Aadhaar numbers pass")
  void verhoeffValidAadhaarNumbersPass(final String candidate) {
    assertThat(PiiValidator.VERHOEFF.accepts(candidate)).isTrue();
  }

  @Test
  @DisplayName("Verhoeff rejects a single transposed digit")
  void verhoeffRejectsASingleTransposedDigit() {
    assertThat(PiiValidator.VERHOEFF.accepts("234567890124")).isTrue();
    // The property that makes the check worth running: adjacent transposition is the commonest
    // human transcription error, and Verhoeff catches all of them.
    assertThat(PiiValidator.VERHOEFF.accepts("234567890142")).isFalse();
    assertThat(PiiValidator.VERHOEFF.accepts("324567890124")).isFalse();
  }

  @Test
  @DisplayName("Verhoeff rejects Aadhaar numbers starting with zero or one")
  void verhoeffRejectsAadhaarNumbersStartingWithZeroOrOne() {
    assertThat(PiiValidator.VERHOEFF.accepts("034567890124")).isFalse();
    assertThat(PiiValidator.VERHOEFF.accepts("134567890124")).isFalse();
  }

  @Test
  @DisplayName("Verhoeff rejects anything that is not twelve digits")
  void verhoeffRejectsAnythingThatIsNotTwelveDigits() {
    assertThat(PiiValidator.VERHOEFF.accepts("23456789012")).isFalse();
    assertThat(PiiValidator.VERHOEFF.accepts("2345678901245")).isFalse();
  }

  @ParameterizedTest(name = "{0} is a valid IPv4 address")
  @ValueSource(strings = {"0.0.0.0", "10.0.0.7", "192.168.1.254", "255.255.255.255"})
  @DisplayName("in-range dotted quads are addresses")
  void inRangeDottedQuadsAreAddresses(final String candidate) {
    assertThat(PiiValidator.IPV4_RANGE.accepts(candidate)).isTrue();
  }

  @ParameterizedTest(name = "{0} is not a valid IPv4 address")
  @ValueSource(
      strings = {"256.1.1.1", "999.888.777.666", "1.2.3", "1.2.3.4.5", "1.2.3.4444", "a.b.c.d"})
  @DisplayName("out-of-range or malformed quads are not addresses")
  void outOfRangeOrMalformedQuadsAreNotAddresses(final String candidate) {
    assertThat(PiiValidator.IPV4_RANGE.accepts(candidate)).isFalse();
  }

  @ParameterizedTest(name = "{0} is a valid coordinate pair")
  @ValueSource(
      strings = {"37.774929, -122.419418", "-33.868820,151.209290", "90.000000, 180.000000"})
  @DisplayName("in-range pairs are coordinates")
  void inRangePairsAreCoordinates(final String candidate) {
    assertThat(PiiValidator.GPS_RANGE.accepts(candidate)).isTrue();
  }

  @ParameterizedTest(name = "{0} is not a valid coordinate pair")
  @ValueSource(
      strings = {"91.000000, 10.000000", "10.000000, 181.000000", "0.000000, 0.000000", "abc, def"})
  @DisplayName("out-of-range or placeholder pairs are not coordinates")
  void outOfRangeOrPlaceholderPairsAreNotCoordinates(final String candidate) {
    assertThat(PiiValidator.GPS_RANGE.accepts(candidate)).isFalse();
  }

  @ParameterizedTest(name = "{0} is a plausible birth date")
  @ValueSource(strings = {"1985-03-21", "21/03/1985", "2000-12-31", "1900-01-01"})
  @DisplayName("plausible birth dates pass")
  void plausibleBirthDatesPass(final String candidate) {
    assertThat(PiiValidator.PLAUSIBLE_DATE.accepts(candidate)).isTrue();
  }

  @ParameterizedTest(name = "{0} is not a plausible birth date")
  @ValueSource(strings = {"1899-01-01", "2099-01-01", "1985-13-21", "1985-03-45", "12345"})
  @DisplayName("impossible dates fail")
  void impossibleDatesFail(final String candidate) {
    assertThat(PiiValidator.PLAUSIBLE_DATE.accepts(candidate)).isFalse();
  }

  @Test
  @DisplayName("the null validator accepts everything")
  void theNullValidatorAcceptsEverything() {
    assertThat(PiiValidator.NONE.accepts("")).isTrue();
    assertThat(PiiValidator.NONE.accepts("anything at all")).isTrue();
  }

  @Test
  @DisplayName("Luhn removes the great majority of random digit runs")
  void luhnRemovesTheGreatMajorityOfRandomDigitRuns() {
    final java.util.Random deterministic = new java.util.Random(20260806L);
    int accepted = 0;
    for (int i = 0; i < 10_000; i++) {
      final StringBuilder digits = new StringBuilder(16);
      for (int d = 0; d < 16; d++) {
        digits.append((char) ('0' + deterministic.nextInt(10)));
      }
      if (PiiValidator.LUHN.accepts(digits.toString())) {
        accepted++;
      }
    }

    // Around one in ten by construction. This is the quantitative claim behind "validators are what
    // make this a detector rather than a regex list": nine in ten false positives removed, at the
    // cost of no true positives at all, because a genuine card carries the check digit.
    assertThat(accepted).isBetween(800, 1200);
  }
}
