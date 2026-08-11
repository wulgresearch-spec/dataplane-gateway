package io.reliabilityai.gateway.dataplane.authn.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKey;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Real-cryptography tests for key resolution and JDK signature verification (Doc 37 §9). Uses
 * genuine JCA key generation and signing — no mocks — to prove RS256/ES256 verification and the
 * attack rejections (unknown/revoked/duplicate kid, algorithm confusion, tampered/forged
 * signatures).
 */
class CryptoVerificationTest {

  private final JdkJwsSignatureVerifier verifier = new JdkJwsSignatureVerifier();

  private static String b64(final PublicKey key) {
    return Base64.getEncoder().encodeToString(key.getEncoded());
  }

  private static VerificationKeySnapshot snapshot(final List<VerificationKey> keys) {
    return new VerificationKeySnapshot(
        new SnapshotVersion("verification-key", "v1"), new Region("us-east-1"), keys, List.of());
  }

  @Test
  void rs256RoundTripVerifiesAndRejectsTampering() throws Exception {
    final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    final KeyPair kp = gen.generateKeyPair();
    final byte[] signingInput = "header.payload".getBytes(StandardCharsets.US_ASCII);
    final Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(kp.getPrivate());
    signer.update(signingInput);
    final byte[] signature = signer.sign();

    assertThat(verifier.verify(JwsAlgorithm.RS256, kp.getPublic(), signingInput, signature))
        .isTrue();
    assertThat(
            verifier.verify(
                JwsAlgorithm.RS256,
                kp.getPublic(),
                "header.TAMPERED".getBytes(StandardCharsets.US_ASCII),
                signature))
        .isFalse();
  }

  @Test
  void es256RoundTripVerifiesAndRejectsForgedZeroSignature() throws Exception {
    final KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
    gen.initialize(new ECGenParameterSpec("secp256r1"));
    final KeyPair kp = gen.generateKeyPair();
    final byte[] signingInput = "header.payload".getBytes(StandardCharsets.US_ASCII);
    final Signature signer = Signature.getInstance("SHA256withECDSA");
    signer.initSign(kp.getPrivate());
    signer.update(signingInput);
    final byte[] jose = derToJose(signer.sign(), 32);

    assertThat(verifier.verify(JwsAlgorithm.ES256, kp.getPublic(), signingInput, jose)).isTrue();
    // CVE-2022-21449: an all-zero (r=0,s=0) signature must never verify.
    assertThat(verifier.verify(JwsAlgorithm.ES256, kp.getPublic(), signingInput, new byte[64]))
        .isFalse();
    // Wrong length fails closed.
    assertThat(verifier.verify(JwsAlgorithm.ES256, kp.getPublic(), signingInput, new byte[10]))
        .isFalse();
  }

  @Test
  void registryResolvesKnownKeyAndReconstructsPublicKey() throws Exception {
    final KeyPair kp = rsa();
    final var registry =
        new VerificationKeyRegistry(
            snapshot(
                List.of(new VerificationKey("kid-1", JwsAlgorithm.RS256, b64(kp.getPublic())))));
    final KeyResolution r = registry.resolve("kid-1", JwsAlgorithm.RS256);
    assertThat(r).isInstanceOf(KeyResolution.Resolved.class);
    assertThat(((KeyResolution.Resolved) r).publicKey()).isEqualTo(kp.getPublic());
  }

  @Test
  void registryRejectsUnknownRevokedAndAlgorithmConfusion() throws Exception {
    final KeyPair kp = rsa();
    final var snapshot =
        new VerificationKeySnapshot(
            new SnapshotVersion("verification-key", "v1"),
            new Region("us-east-1"),
            List.of(new VerificationKey("kid-1", JwsAlgorithm.RS256, b64(kp.getPublic()))),
            List.of("kid-revoked"));
    final var registry = new VerificationKeyRegistry(snapshot);

    assertRejected(
        registry.resolve("kid-unknown", JwsAlgorithm.RS256),
        AuthenticationFailureReason.UNKNOWN_KEY);
    assertRejected(
        registry.resolve("kid-revoked", JwsAlgorithm.RS256),
        AuthenticationFailureReason.KEY_REVOKED);
    // kid-1 is bound to RS256; presenting ES256 is algorithm confusion.
    assertRejected(
        registry.resolve("kid-1", JwsAlgorithm.ES256),
        AuthenticationFailureReason.ALGORITHM_MISMATCH);
    assertRejected(
        registry.resolve(" ", JwsAlgorithm.RS256), AuthenticationFailureReason.UNKNOWN_KEY);
  }

  @Test
  void registryRejectsDuplicateKidSnapshot() throws Exception {
    final KeyPair kp = rsa();
    final var dup =
        List.of(
            new VerificationKey("kid-1", JwsAlgorithm.RS256, b64(kp.getPublic())),
            new VerificationKey("kid-1", JwsAlgorithm.RS256, b64(kp.getPublic())));
    assertThatThrownBy(() -> new VerificationKeyRegistry(snapshot(dup)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate kid");
  }

  @Test
  void algNoneAndHs256AreNotOnTheAllowList() {
    assertThat(JwsAlgorithm.fromHeader("none")).isEmpty();
    assertThat(JwsAlgorithm.fromHeader("HS256")).isEmpty();
    assertThat(JwsAlgorithm.fromHeader("RS256")).contains(JwsAlgorithm.RS256);
    assertThat(JwsAlgorithm.fromHeader("ES256")).contains(JwsAlgorithm.ES256);
  }

  private static void assertRejected(
      final KeyResolution resolution, final AuthenticationFailureReason reason) {
    assertThat(resolution).isInstanceOf(KeyResolution.Rejected.class);
    assertThat(((KeyResolution.Rejected) resolution).reason()).isEqualTo(reason);
  }

  private static KeyPair rsa() throws Exception {
    final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    return gen.generateKeyPair();
  }

  /** Converts a JCA DER ECDSA signature to the JOSE fixed-width R||S form (test helper). */
  private static byte[] derToJose(final byte[] der, final int componentBytes) {
    int offset = (der[1] & 0x80) == 0 ? 2 : 3; // skip SEQUENCE tag + (short/long) length
    // r INTEGER
    offset++; // 0x02
    int rLen = der[offset++];
    final byte[] r = Arrays.copyOfRange(der, offset, offset + rLen);
    offset += rLen;
    offset++; // 0x02
    int sLen = der[offset++];
    final byte[] s = Arrays.copyOfRange(der, offset, offset + sLen);
    final byte[] jose = new byte[componentBytes * 2];
    copyRightAligned(new BigInteger(1, r), jose, 0, componentBytes);
    copyRightAligned(new BigInteger(1, s), jose, componentBytes, componentBytes);
    return jose;
  }

  private static void copyRightAligned(
      final BigInteger value, final byte[] dest, final int start, final int len) {
    byte[] b = value.toByteArray();
    if (b.length > 1 && b[0] == 0) {
      b = Arrays.copyOfRange(b, 1, b.length); // strip sign byte
    }
    System.arraycopy(b, 0, dest, start + len - b.length, b.length);
  }
}
