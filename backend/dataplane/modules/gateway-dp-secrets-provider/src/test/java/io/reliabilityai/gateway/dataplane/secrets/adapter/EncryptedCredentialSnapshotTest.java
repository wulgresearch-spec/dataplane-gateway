package io.reliabilityai.gateway.dataplane.secrets.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Structural validation of the encrypted credential snapshot (Doc 26 §12(a)).
 *
 * <p>The snapshot is the only thing that tells the materialization path how large a buffer to
 * allocate for the decrypted credential, so a non-positive length is not a cosmetic argument error
 * — it would size a lease buffer that cannot hold a credential at all.
 */
class EncryptedCredentialSnapshotTest {

  private static CredentialSnapshotRef ref() {
    return new CredentialSnapshotRef(
        new SnapshotVersion("credential", "v1"),
        TenantScope.of("org-1", "tenant-1"),
        Instant.parse("2999-01-01T00:00:00Z"));
  }

  private static EncryptedCredentialSnapshot snapshot(final int plaintextLength) {
    return new EncryptedCredentialSnapshot(
        ref(), new byte[44], new byte[12], new byte[32], new byte[0], plaintextLength);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
  void rejectsNonPositivePlaintextLength(final int invalid) {
    // Zero is the boundary that matters. A `<= 0` that drifted to `< 0` would admit a zero-length
    // credential as if it were valid.
    assertThatThrownBy(() -> snapshot(invalid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("plaintextLength must be positive");
  }

  @Test
  void acceptsSmallestPositivePlaintextLength() {
    assertThat(snapshot(1).plaintextLength()).isEqualTo(1);
  }

  @Test
  void exposesTheContentFreeRef() {
    assertThat(snapshot(16).ref()).isEqualTo(ref());
  }
}
