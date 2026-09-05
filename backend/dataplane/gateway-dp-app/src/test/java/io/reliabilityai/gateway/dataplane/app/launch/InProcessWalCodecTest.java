package io.reliabilityai.gateway.dataplane.app.launch;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.common.ContentFree;
import org.junit.jupiter.api.Test;

/**
 * The codec's contract is narrow and its limit is the point: a payload survives a round trip inside
 * one process, and anything else degrades to a visible {@code Unrecoverable} rather than throwing
 * or, worse, decoding to the wrong payload.
 */
class InProcessWalCodecTest {

  private record Fact(String value) implements ContentFree {}

  private final InProcessWalCodec codec = new InProcessWalCodec();

  @Test
  void returnsThePayloadItEncoded() {
    final Fact fact = new Fact("usage");

    assertThat(codec.decode("topic", codec.encode(fact))).isSameAs(fact);
  }

  @Test
  void keepsSeparatePayloadsDistinct() {
    final Fact first = new Fact("first");
    final Fact second = new Fact("second");

    final byte[] firstBytes = codec.encode(first);
    final byte[] secondBytes = codec.encode(second);

    assertThat(codec.decode("topic", firstBytes)).isSameAs(first);
    assertThat(codec.decode("topic", secondBytes)).isSameAs(second);
  }

  @Test
  void reportsAnEntryFromAPreviousRunAsUnrecoverable() {
    // What a restart over a non-empty log looks like: this codec never wrote index 7.
    final byte[] foreign = new byte[] {1, 0, 0, 0, 7};

    assertThat(codec.decode("usage", foreign))
        .isEqualTo(new InProcessWalCodec.Unrecoverable("usage"));
  }

  @Test
  void reportsMalformedBytesAsUnrecoverableRatherThanThrowing() {
    assertThat(codec.decode("usage", new byte[0]))
        .isEqualTo(new InProcessWalCodec.Unrecoverable("usage"));
    assertThat(codec.decode("usage", new byte[] {9, 9}))
        .isEqualTo(new InProcessWalCodec.Unrecoverable("usage"));
    assertThat(codec.decode("usage", null)).isEqualTo(new InProcessWalCodec.Unrecoverable("usage"));
  }

  @Test
  void rejectsAnUnknownTagRatherThanTrustingItsIndex() {
    codec.encode(new Fact("present"));
    // Right length, right index, wrong tag: a decoder that ignored the tag would hand back a
    // payload the writer never wrote.
    final byte[] wrongTag = new byte[] {0, 0, 0, 0, 0};

    assertThat(codec.decode("usage", wrongTag))
        .isEqualTo(new InProcessWalCodec.Unrecoverable("usage"));
  }
}
