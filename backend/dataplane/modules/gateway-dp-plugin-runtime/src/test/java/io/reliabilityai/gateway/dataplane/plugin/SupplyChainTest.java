package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginSignaturePort;
import io.reliabilityai.gateway.dataplane.plugin.api.VettedPluginSnapshot;
import io.reliabilityai.gateway.dataplane.plugin.internal.PinnedAnchorSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Signature, digest and provenance verification against the pinned anchor (Doc 28 §STC). */
@DisplayName("supply-chain verification")
class SupplyChainTest {

  private static final String ALGORITHM = "SHA256withRSA";

  private static KeyPair anchor;
  private static KeyPair impostor;

  @BeforeAll
  static void generateKeys() throws Exception {
    final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    anchor = generator.generateKeyPair();
    impostor = generator.generateKeyPair();
  }

  private static PinnedAnchorSignatureVerifier verifier() {
    return new PinnedAnchorSignatureVerifier(anchor.getPublic(), ALGORITHM);
  }

  private static byte[] sign(final PrivateKey key, final byte[] digest, final String provenance)
      throws Exception {
    final Signature signer = Signature.getInstance(ALGORITHM);
    signer.initSign(key);
    signer.update(digest);
    signer.update(provenance.getBytes(StandardCharsets.UTF_8));
    return signer.sign();
  }

  private static VettedPluginSnapshot signedSnapshot(
      final String id, final byte[] artifact, final PrivateKey key, final String provenance)
      throws Exception {
    final PluginManifest manifest = PluginFixture.toolManifest(id);
    final byte[] digest = PinnedAnchorSignatureVerifier.digestOf(artifact);
    return new VettedPluginSnapshot(
        manifest, artifact, digest, sign(key, digest, provenance), provenance);
  }

  @Test
  @DisplayName("a correctly signed snapshot is trusted")
  void correctlySignedSnapshotIsTrusted() throws Exception {
    final VettedPluginSnapshot snapshot =
        signedSnapshot(
            "genuine",
            "plugin-bytes".getBytes(StandardCharsets.UTF_8),
            anchor.getPrivate(),
            "vetted-by=c12");

    assertThat(verifier().verify(snapshot)).isEqualTo(PluginSignaturePort.Verdict.TRUSTED);
  }

  @Test
  @DisplayName("a snapshot signed by a different key is refused, with no trust-on-first-use")
  void unknownSignerIsRefused() throws Exception {
    final VettedPluginSnapshot snapshot =
        signedSnapshot(
            "forged",
            "plugin-bytes".getBytes(StandardCharsets.UTF_8),
            impostor.getPrivate(),
            "vetted-by=c12");

    // Doc 28 STC-6: the anchor is pinned. A valid signature from the wrong signer is still wrong.
    assertThat(verifier().verify(snapshot))
        .isEqualTo(PluginSignaturePort.Verdict.SIGNATURE_INVALID);
  }

  @Test
  @DisplayName("artifact bytes swapped after signing are caught by the digest check")
  void substitutedArtifactIsCaught() throws Exception {
    final byte[] original = "safe-plugin".getBytes(StandardCharsets.UTF_8);
    final byte[] digest = PinnedAnchorSignatureVerifier.digestOf(original);
    final String provenance = "vetted-by=c12";
    final byte[] signature = sign(anchor.getPrivate(), digest, provenance);

    // A valid signature over a digest that no longer describes the artifact. Verifying the
    // signature
    // and trusting the claimed digest without rehashing would let this through.
    final VettedPluginSnapshot substituted =
        new VettedPluginSnapshot(
            PluginFixture.toolManifest("substituted"),
            "malicious-plugin".getBytes(StandardCharsets.UTF_8),
            digest,
            signature,
            provenance);

    assertThat(verifier().verify(substituted))
        .isEqualTo(PluginSignaturePort.Verdict.DIGEST_MISMATCH);
  }

  @Test
  @DisplayName("a tampered digest is refused")
  void tamperedDigestIsRefused() throws Exception {
    final byte[] artifact = "plugin".getBytes(StandardCharsets.UTF_8);
    final byte[] digest = PinnedAnchorSignatureVerifier.digestOf(artifact);
    digest[0] ^= 0xFF;

    final VettedPluginSnapshot snapshot =
        new VettedPluginSnapshot(
            PluginFixture.toolManifest("tampered"),
            artifact,
            digest,
            sign(anchor.getPrivate(), digest, "vetted-by=c12"),
            "vetted-by=c12");

    assertThat(verifier().verify(snapshot)).isEqualTo(PluginSignaturePort.Verdict.DIGEST_MISMATCH);
  }

  @Test
  @DisplayName("swapping the provenance while keeping the signature is refused")
  void swappedProvenanceIsRefused() throws Exception {
    final byte[] artifact = "plugin".getBytes(StandardCharsets.UTF_8);
    final byte[] digest = PinnedAnchorSignatureVerifier.digestOf(artifact);
    final byte[] signature = sign(anchor.getPrivate(), digest, "vetted-by=c12");

    // The provenance is covered by the signature, so it cannot be exchanged for another vetting
    // statement while keeping the proof (Doc 28 STC-4).
    final VettedPluginSnapshot snapshot =
        new VettedPluginSnapshot(
            PluginFixture.toolManifest("reprovenanced"),
            artifact,
            digest,
            signature,
            "vetted-by=someone-else");

    assertThat(verifier().verify(snapshot))
        .isEqualTo(PluginSignaturePort.Verdict.SIGNATURE_INVALID);
  }

  @Test
  @DisplayName("a null snapshot is refused rather than throwing")
  void nullSnapshotIsRefused() {
    assertThat(verifier().verify(null)).isEqualTo(PluginSignaturePort.Verdict.SIGNATURE_INVALID);
  }

  @Test
  @DisplayName("only TRUSTED admits loading")
  void onlyTrustedAdmitsLoading() {
    assertThat(PluginSignaturePort.Verdict.TRUSTED.trusted()).isTrue();
    for (final PluginSignaturePort.Verdict verdict : PluginSignaturePort.Verdict.values()) {
      if (verdict != PluginSignaturePort.Verdict.TRUSTED) {
        assertThat(verdict.trusted()).as("%s must not admit loading", verdict).isFalse();
      }
    }
  }

  @Test
  @DisplayName("an empty artifact still verifies when correctly signed")
  void emptyArtifactVerifies() throws Exception {
    // A plugin whose code is already resident carries no artifact bytes; its digest is still
    // signed.
    final VettedPluginSnapshot snapshot =
        signedSnapshot("resident", new byte[0], anchor.getPrivate(), "vetted-by=c12");

    assertThat(verifier().verify(snapshot)).isEqualTo(PluginSignaturePort.Verdict.TRUSTED);
  }

  @Test
  @DisplayName("the signing input is the digest followed by the provenance")
  void signingInputIsStable() {
    final byte[] input = PinnedAnchorSignatureVerifier.signingInput(new byte[] {1, 2}, "abc");
    assertThat(input).containsExactly(1, 2, 'a', 'b', 'c');
  }

  @Test
  @DisplayName("the digest helper matches what the verifier computes")
  void digestHelperMatchesTheVerifier() throws Exception {
    final byte[] artifact = "consistency".getBytes(StandardCharsets.UTF_8);
    final VettedPluginSnapshot snapshot =
        signedSnapshot("consistent", artifact, anchor.getPrivate(), "vetted-by=c12");

    assertThat(snapshot.digest()).containsExactly(PinnedAnchorSignatureVerifier.digestOf(artifact));
    assertThat(verifier().verify(snapshot)).isEqualTo(PluginSignaturePort.Verdict.TRUSTED);
  }
}
