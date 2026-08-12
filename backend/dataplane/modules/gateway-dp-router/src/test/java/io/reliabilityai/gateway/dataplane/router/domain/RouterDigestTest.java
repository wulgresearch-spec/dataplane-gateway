package io.reliabilityai.gateway.dataplane.router.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;

/**
 * Pins the exact ordering-key encoding.
 *
 * <p>The key decides which of several equally-scored candidates becomes the primary route. Every
 * node that handles a retry of the same request has to reach the same answer independently, so the
 * contract is the encoding itself and not merely that the function is stable within one process.
 *
 * <p>Determinism, length and distribution were already covered through the router, and none of them
 * is sufficient: an encoding that dropped half the digest, or emitted a constant for every high
 * nibble, would still be perfectly deterministic and still spread work across candidates. Only the
 * value pins the encoding.
 */
class RouterDigestTest {

  @Test
  void theOrderKeyIsTheFirstSixteenDigestBytesAsLowercaseHex() throws Exception {
    // Computed here from the JDK's own SHA-256 rather than pasted as a literal, so the assertion
    // states the rule - separator, algorithm, prefix length, byte order and hex form - instead of
    // a constant whose provenance a later reader has to take on trust.
    final byte[] digest =
        MessageDigest.getInstance("SHA-256")
            .digest("corr-1\0cand-a".getBytes(StandardCharsets.UTF_8));
    final StringBuilder expected = new StringBuilder(32);
    for (int i = 0; i < 16; i++) {
      expected.append(String.format("%02x", digest[i] & 0xFF));
    }

    final String actual = RouterDigest.orderKey("corr-1", "cand-a");

    assertThat(actual).isEqualTo(expected.toString());
    assertThat(actual).hasSize(32);
    assertThat(actual).matches("[0-9a-f]{32}");
    // Both nibbles of every byte are carried, in order: high nibble first, then low.
    assertThat(actual.charAt(0)).isEqualTo(Character.forDigit((digest[0] >> 4) & 0xF, 16));
    assertThat(actual.charAt(1)).isEqualTo(Character.forDigit(digest[0] & 0xF, 16));
    assertThat(actual.charAt(30)).isEqualTo(Character.forDigit((digest[15] >> 4) & 0xF, 16));
    assertThat(actual.charAt(31)).isEqualTo(Character.forDigit(digest[15] & 0xF, 16));
  }

  @Test
  void theSeparatorKeepsDifferentPairsFromCollapsingOntoOneKey() {
    // Without a delimiter between the two ids, ("a","bc") and ("ab","c") would hash the same bytes
    // and tie-break identically - one candidate could inherit another's ordering by nothing more
    // than where the boundary between the ids happened to fall.
    assertThat(RouterDigest.orderKey("a", "bc")).isNotEqualTo(RouterDigest.orderKey("ab", "c"));
  }

  @Test
  void theSameInputsAlwaysProduceTheSameKey() {
    assertThat(RouterDigest.orderKey("corr-1", "cand-a"))
        .isEqualTo(RouterDigest.orderKey("corr-1", "cand-a"));
    assertThat(RouterDigest.orderKey("corr-1", "cand-a"))
        .isNotEqualTo(RouterDigest.orderKey("corr-2", "cand-a"));
  }
}
