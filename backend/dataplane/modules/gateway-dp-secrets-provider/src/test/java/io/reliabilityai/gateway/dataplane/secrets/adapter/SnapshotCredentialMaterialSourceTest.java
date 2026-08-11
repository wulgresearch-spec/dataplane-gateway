package io.reliabilityai.gateway.dataplane.secrets.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Clone-on-write / clone-on-read / zeroization tests for the master material (Doc 26 §17.1/MSC).
 */
class SnapshotCredentialMaterialSourceTest {

  private static final CredentialSnapshotRef REF =
      new CredentialSnapshotRef(
          new SnapshotVersion("credential", "v1"),
          TenantScope.of("org-1", "tenant-1"),
          Instant.parse("2999-01-01T00:00:00Z"));

  @Test
  void clonesOnWriteSoSourceArrayMutationDoesNotLeak() {
    final char[] src = "abc".toCharArray();
    final var source = new SnapshotCredentialMaterialSource(REF, src);
    src[0] = 'X'; // mutate the caller's array after construction
    final char[] dest = new char[3];
    source.copyInto(dest);
    assertThat(dest).containsExactly('a', 'b', 'c');
  }

  @Test
  void clonesOnReadSoDestinationMutationDoesNotAffectMaster() {
    final var source = new SnapshotCredentialMaterialSource(REF, "abc".toCharArray());
    final char[] dest = new char[3];
    source.copyInto(dest);
    dest[0] = 'Z';
    final char[] dest2 = new char[3];
    source.copyInto(dest2);
    assertThat(dest2).containsExactly('a', 'b', 'c');
  }

  @Test
  void copyAfterZeroizeFailsClosed() {
    final var source = new SnapshotCredentialMaterialSource(REF, "abc".toCharArray());
    source.zeroize();
    assertThat(source.isEvicted()).isTrue();
    assertThatThrownBy(() -> source.copyInto(new char[3]))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("evicted");
  }

  @Test
  void zeroizeIsIdempotent() {
    final var source = new SnapshotCredentialMaterialSource(REF, "abc".toCharArray());
    source.zeroize();
    source.zeroize();
    assertThat(source.isEvicted()).isTrue();
  }

  @Test
  void rejectsTooSmallDestination() {
    final var source = new SnapshotCredentialMaterialSource(REF, "abc".toCharArray());
    assertThatThrownBy(() -> source.copyInto(new char[2]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exposesRefAndLength() {
    final var source = new SnapshotCredentialMaterialSource(REF, "abc".toCharArray());
    assertThat(source.ref()).isEqualTo(REF);
    assertThat(source.length()).isEqualTo(3);
  }

  @Test
  void rejectsNullArguments() {
    assertThatThrownBy(() -> new SnapshotCredentialMaterialSource(null, new char[1]))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SnapshotCredentialMaterialSource(REF, null))
        .isInstanceOf(NullPointerException.class);
  }
}
