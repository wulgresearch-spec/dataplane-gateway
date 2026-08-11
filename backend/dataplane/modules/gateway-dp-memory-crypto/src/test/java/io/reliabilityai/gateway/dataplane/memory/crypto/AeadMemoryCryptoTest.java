package io.reliabilityai.gateway.dataplane.memory.crypto;

import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_ALICE;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_ALICE_DRAFTS;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_ALICE_NOTES;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_BOB;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.GLOBEX_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Round trips, and every way a sealed body can fail to open. */
@DisplayName("AES-256-GCM envelope encryption")
final class AeadMemoryCryptoTest {

  private CryptoFixtures.TestKeys keys;
  private InProcessCryptoMetrics metrics;
  private AeadMemoryCrypto crypto;

  @BeforeEach
  void setUp() {
    keys = new CryptoFixtures.TestKeys("2026-07");
    metrics = new InProcessCryptoMetrics();
    crypto = CryptoFixtures.crypto(keys, metrics);
  }

  @Test
  @DisplayName("a sealed body opens back to exactly what was sealed")
  void aSealedBodyOpensBackToExactlyWhatWasSealed() {
    final String plaintext = "the quarterly forecast was revised down on 2026-07-14";
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, plaintext);

    assertThat(crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef())).contains(plaintext);
  }

  @Test
  @DisplayName("the ciphertext never contains the plaintext")
  void theCiphertextNeverContainsThePlaintext() {
    final String plaintext = "patient-identifier-88213";
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, plaintext);

    // Both the encoded form and the decoded bytes, because a base64 boundary can hide a substring
    // from a naive check while leaving it perfectly legible on disk.
    assertThat(sealed.ciphertext()).doesNotContain(plaintext);
    assertThat(new String(CryptoFixtures.rawOf(sealed.ciphertext()), StandardCharsets.ISO_8859_1))
        .doesNotContain(plaintext);
  }

  @Test
  @DisplayName("the key reference is the key version and carries no key material")
  void theKeyReferenceIsTheKeyVersionAndCarriesNoKeyMaterial() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");

    assertThat(sealed.keyRef()).isEqualTo("2026-07");
  }

  @ParameterizedTest(name = "body of {0} characters survives the round trip")
  @ValueSource(ints = {1, 2, 15, 16, 17, 31, 32, 33, 1023, 4096, 65_536})
  @DisplayName("bodies at and around every block boundary survive")
  void bodiesAtAndAroundEveryBlockBoundarySurvive(final int length) {
    final String plaintext = "x".repeat(length);
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, plaintext);

    assertThat(crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef())).contains(plaintext);
  }

  @Test
  @DisplayName("an empty body survives the round trip")
  void anEmptyBodySurvivesTheRoundTrip() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "");

    assertThat(crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef())).contains("");
  }

  @Test
  @DisplayName("multi-byte text survives byte-for-byte")
  void multiByteTextSurvivesByteForByte() {
    final String plaintext = "ここに秘密があります 🔐 naïve café — ĳ";
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, plaintext);

    assertThat(crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef())).contains(plaintext);
  }

  @Test
  @DisplayName("sealing the same body twice produces different ciphertexts")
  void sealingTheSameBodyTwiceProducesDifferentCiphertexts() {
    final MemoryCryptoPort.Sealed first = crypto.seal(ACME_ALICE, "identical body");
    final MemoryCryptoPort.Sealed second = crypto.seal(ACME_ALICE, "identical body");

    // Without this, storage becomes an equality oracle: an observer who cannot decrypt anything can
    // still see which records hold the same content, across tenants.
    assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
  }

  @Test
  @DisplayName("a thousand seals of one body produce a thousand distinct ciphertexts")
  void aThousandSealsOfOneBodyProduceAThousandDistinctCiphertexts() {
    final Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      seen.add(crypto.seal(ACME_ALICE, "same").ciphertext());
    }

    assertThat(seen).hasSize(1000);
  }

  @Test
  @DisplayName("every seal draws a fresh data key, so no two records share one")
  void everySealDrawsAFreshDataKeySoNoTwoRecordsShareOne() {
    final Set<String> wrappedKeys = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      final byte[] raw = CryptoFixtures.rawOf(crypto.seal(ACME_ALICE, "same").ciphertext());
      // The wrapped key sits after magic(3) format(1) versionLen(1) version(7) nonce(12) len(2).
      wrappedKeys.add(Arrays.toString(Arrays.copyOfRange(raw, 26, 26 + 48)));
    }

    assertThat(wrappedKeys).hasSize(200);
  }

  @Test
  @DisplayName("a body sealed for one tenant will not open for another")
  void aBodySealedForOneTenantWillNotOpenForAnother() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_TENANT, "acme internal");

    assertThat(crypto.unseal(GLOBEX_TENANT, sealed.ciphertext(), sealed.keyRef())).isEmpty();
    assertThat(metrics.count("openFailed.AUTHENTICATION_FAILED")).isEqualTo(1);
  }

  @Test
  @DisplayName("a body sealed for one user will not open for another in the same tenant")
  void aBodySealedForOneUserWillNotOpenForAnotherInTheSameTenant() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "alice private");

    assertThat(crypto.unseal(ACME_BOB, sealed.ciphertext(), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("a user-private body will not open at tenant scope")
  void aUserPrivateBodyWillNotOpenAtTenantScope() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "alice private");

    // Widening is as much an attack as switching: a tenant-scope read must not reach into a user's
    // private content just because it stayed inside the tenant.
    assertThat(crypto.unseal(ACME_TENANT, sealed.ciphertext(), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("a partitioned body will not open in a sibling partition")
  void aPartitionedBodyWillNotOpenInASiblingPartition() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE_NOTES, "notes body");

    assertThat(crypto.unseal(ACME_ALICE_DRAFTS, sealed.ciphertext(), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("a partitioned body will not open unpartitioned")
  void aPartitionedBodyWillNotOpenUnpartitioned() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE_NOTES, "notes body");

    assertThat(crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("every distinct scope refuses every other scope's ciphertext")
  void everyDistinctScopeRefusesEveryOtherScopesCiphertext() {
    final List<MemoryScope> scopes =
        List.of(
            ACME_TENANT, GLOBEX_TENANT, ACME_ALICE, ACME_BOB, ACME_ALICE_NOTES, ACME_ALICE_DRAFTS);

    // The cross-product rather than a few samples, because scope binding that works for the pairs
    // someone thought to test is not scope binding.
    for (final MemoryScope sealedFor : scopes) {
      final MemoryCryptoPort.Sealed sealed = crypto.seal(sealedFor, "body for " + sealedFor.key());
      for (final MemoryScope openedBy : scopes) {
        final Optional<String> opened =
            crypto.unseal(openedBy, sealed.ciphertext(), sealed.keyRef());
        if (sealedFor.equals(openedBy)) {
          assertThat(opened).as("%s opening its own", sealedFor.key()).isPresent();
        } else {
          assertThat(opened).as("%s opening %s", openedBy.key(), sealedFor.key()).isEmpty();
        }
      }
    }
  }

  @Test
  @DisplayName("a body sealed under one key will not open under another")
  void aBodySealedUnderOneKeyWillNotOpenUnderAnother() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");
    final CryptoFixtures.TestKeys otherDeployment = new CryptoFixtures.TestKeys("2026-07");
    final AeadMemoryCrypto elsewhere =
        CryptoFixtures.crypto(otherDeployment, new InProcessCryptoMetrics());

    // Same version label, different material: the label is not what authorises the open.
    assertThat(elsewhere.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName(
      "a key reference naming a version we cannot load reports as unknown, not as tampering")
  void aKeyReferenceNamingAVersionWeCannotLoadReportsAsUnknownNotAsTampering() {
    keys.add("2026-10");
    crypto.rotateTo("2026-10");
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");
    keys.retire("2026-10");

    assertThat(crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef())).isEmpty();
    assertThat(metrics.count("openFailed.UNKNOWN_KEY_VERSION")).isEqualTo(1);
    assertThat(metrics.count("openFailed.AUTHENTICATION_FAILED")).isZero();
  }

  @Test
  @DisplayName("an edited key reference is caught before a key is ever selected")
  void anEditedKeyReferenceIsCaughtBeforeAKeyIsEverSelected() {
    keys.add("2026-04");
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");

    assertThat(crypto.unseal(ACME_ALICE, sealed.ciphertext(), "2026-04")).isEmpty();
    assertThat(metrics.count("openFailed.KEY_REF_MISMATCH")).isEqualTo(1);
  }

  @Test
  @DisplayName("flipping any single bit of the envelope refuses the open")
  void flippingAnySingleBitOfTheEnvelopeRefusesTheOpen() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "a body long enough to matter");
    final int length = CryptoFixtures.rawOf(sealed.ciphertext()).length;

    // Every byte, not a sampled few. A format where some region is unauthenticated is a format
    // where
    // exactly that region gets attacked.
    for (int index = 0; index < length; index++) {
      final String corrupted = CryptoFixtures.flipBit(sealed.ciphertext(), index);
      assertThat(crypto.unseal(ACME_ALICE, corrupted, sealed.keyRef()))
          .as("bit flip at byte %d was accepted", index)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("truncating the envelope anywhere refuses the open without throwing")
  void truncatingTheEnvelopeAnywhereRefusesTheOpenWithoutThrowing() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "a body long enough to matter");
    final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());

    for (int keep = 0; keep < raw.length; keep++) {
      final String shortened = CryptoFixtures.encodeRaw(Arrays.copyOf(raw, keep));
      assertThat(crypto.unseal(ACME_ALICE, shortened, sealed.keyRef()))
          .as("truncation to %d bytes was accepted", keep)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("appending bytes to the envelope refuses the open")
  void appendingBytesToTheEnvelopeRefusesTheOpen() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");
    final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());
    final byte[] extended = Arrays.copyOf(raw, raw.length + 8);

    assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(extended), sealed.keyRef()))
        .isEmpty();
  }

  @Test
  @DisplayName("arbitrary text offered as a sealed body is refused as malformed")
  void arbitraryTextOfferedAsASealedBodyIsRefusedAsMalformed() {
    assertThat(crypto.unseal(ACME_ALICE, "not-an-envelope-at-all", "2026-07")).isEmpty();
    assertThat(metrics.count("openFailed.MALFORMED_ENVELOPE")).isEqualTo(1);
  }

  @Test
  @DisplayName("a body that is not base64 is refused as malformed")
  void aBodyThatIsNotBase64IsRefusedAsMalformed() {
    assertThat(crypto.unseal(ACME_ALICE, "!!!! not base64 !!!!", "2026-07")).isEmpty();
    assertThat(metrics.count("openFailed.MALFORMED_ENVELOPE")).isEqualTo(1);
  }

  @Test
  @DisplayName("an empty string offered as a sealed body is refused")
  void anEmptyStringOfferedAsASealedBodyIsRefused() {
    assertThat(crypto.unseal(ACME_ALICE, "", "2026-07")).isEmpty();
  }

  @Test
  @DisplayName("an envelope claiming an unsupported format is refused")
  void anEnvelopeClaimingAnUnsupportedFormatIsRefused() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");
    final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());
    raw[3] = 99;

    // Refusing an unknown format rather than guessing is what makes a future format change safe.
    assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(raw), sealed.keyRef())).isEmpty();
    assertThat(metrics.count("openFailed.MALFORMED_ENVELOPE")).isEqualTo(1);
  }

  @Test
  @DisplayName("an envelope with foreign magic is refused")
  void anEnvelopeWithForeignMagicIsRefused() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");
    final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());
    raw[0] = 'X';

    assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(raw), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("an envelope declaring more bytes than it carries is refused")
  void anEnvelopeDeclaringMoreBytesThanItCarriesIsRefused() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");
    final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());
    // The wrapped-key length lives at offset 24..25 for a seven-character version label.
    raw[24] = (byte) 0x7f;
    raw[25] = (byte) 0xff;

    assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(raw), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("the wrapped data key of one record cannot be pasted into another")
  void theWrappedDataKeyOfOneRecordCannotBePastedIntoAnother() {
    final MemoryCryptoPort.Sealed donor = crypto.seal(ACME_ALICE, "donor body");
    final MemoryCryptoPort.Sealed target = crypto.seal(ACME_ALICE, "target body");
    final byte[] donorRaw = CryptoFixtures.rawOf(donor.ciphertext());
    final byte[] targetRaw = CryptoFixtures.rawOf(target.ciphertext());
    System.arraycopy(donorRaw, 12, targetRaw, 12, 12 + 2 + 48);

    assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(targetRaw), target.keyRef()))
        .isEmpty();
  }

  @Test
  @DisplayName("sealing refuses when the primary key cannot be loaded")
  void sealingRefusesWhenThePrimaryKeyCannotBeLoaded() {
    keys.retire("2026-07");

    // Refusing the write is the only safe answer. Falling back to plaintext because the key is
    // missing would turn a key outage into a silent, permanent disclosure.
    assertThatThrownBy(() -> crypto.seal(ACME_ALICE, "body"))
        .isInstanceOf(MemoryStoreUnavailableException.class);
  }

  @Test
  @DisplayName("opening never throws, whatever it is handed")
  void openingNeverThrowsWhateverItIsHanded() {
    final List<String> hostile =
        List.of(
            "",
            "a",
            "====",
            "TUsx",
            "TUsxAQ",
            "0000000000000000000000000000000000000000",
            "-".repeat(1000));

    // The read pipeline treats an unopenable record as undecryptable and continues. An exception
    // here would turn one bad record into a failed read for every record beside it.
    for (final String candidate : hostile) {
      assertThat(crypto.unseal(ACME_ALICE, candidate, "2026-07"))
          .as("input %s", candidate)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("the failure reason is reported to metrics and never to the caller")
  void theFailureReasonIsReportedToMetricsAndNeverToTheCaller() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");

    final Optional<String> wrongTenant =
        crypto.unseal(GLOBEX_TENANT, sealed.ciphertext(), sealed.keyRef());
    final Optional<String> malformed = crypto.unseal(ACME_ALICE, "rubbish", sealed.keyRef());

    // Both are indistinguishable to the caller — same type, same emptiness, no message anywhere —
    // while the operator still gets two different counters.
    assertThat(wrongTenant).isEqualTo(malformed).isEmpty();
    assertThat(metrics.count("openFailed.AUTHENTICATION_FAILED")).isEqualTo(1);
    assertThat(metrics.count("openFailed.MALFORMED_ENVELOPE")).isEqualTo(1);
  }
}
