package io.reliabilityai.gateway.dataplane.secrets.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.ports.ClockPort;
import java.io.Serializable;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Lifecycle and zeroization tests for the credential lease (Doc 26 §11.1/§17.1/§20.1, CLC-8). */
class SanitizableCredentialLeaseTest {

  private static final TenantScope TENANT = TenantScope.of("org-1", "tenant-1");
  private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
  private static final Instant FAR_FUTURE = Instant.parse("2999-01-01T00:00:00Z");
  private static final ClockPort CLOCK = () -> NOW;

  private static SanitizableCredentialLease lease(final char[] material, final Runnable onLeak) {
    return new SanitizableCredentialLease("lease-1", TENANT, FAR_FUTURE, material, onLeak, CLOCK);
  }

  @Test
  void materialIsZeroizedOnCloseAndLeaseBecomesInactive() {
    final char[] material = "s3cr3t".toCharArray();
    final boolean[] leaked = {false};
    final var lease = lease(material, () -> leaked[0] = true);
    assertThat(lease.active()).isTrue();

    final char[][] observed = new char[1][];
    lease.use(m -> observed[0] = m.clone());
    assertThat(observed[0]).containsExactly('s', '3', 'c', 'r', '3', 't');

    lease.close();
    assertThat(lease.active()).isFalse();
    assertThat(material).containsOnly((char) 0); // deterministically overwritten (MSC-2)
    assertThat(leaked[0]).isFalse(); // explicit close is not a leak
  }

  @Test
  void useAfterCloseThrows() {
    final var lease = lease("ab".toCharArray(), () -> {});
    lease.close();
    assertThatThrownBy(() -> lease.use(m -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not active");
  }

  @Test
  void secondUseThrowsSingleUse() {
    final var lease = lease("ab".toCharArray(), () -> {});
    lease.use(m -> {});
    assertThatThrownBy(() -> lease.use(m -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already used");
  }

  @Test
  void closeIsIdempotent() {
    final char[] material = "ab".toCharArray();
    final var lease = lease(material, () -> {});
    lease.close();
    lease.close(); // no throw, still zeroized
    assertThat(material).containsOnly((char) 0);
  }

  @Test
  void leaseExposesBindingMetadataButNotMaterial() {
    final var lease = lease("ab".toCharArray(), () -> {});
    assertThat(lease.leaseId()).isEqualTo("lease-1");
    assertThat(lease.tenantScope()).isEqualTo(TENANT);
    assertThat(lease.notAfter()).isEqualTo(FAR_FUTURE);
  }

  @Test
  void expiredLeaseIsInactiveAndRefusesUse() {
    // F-1: a lease whose notAfter is in the past (relative to the injected clock) is inactive and
    // must refuse use — expired material is never applied (Doc 26 §21, SP-D3).
    final Instant past = NOW.minusSeconds(1);
    final var expired =
        new SanitizableCredentialLease("l-exp", TENANT, past, "ab".toCharArray(), () -> {}, CLOCK);
    assertThat(expired.active()).isFalse();
    assertThatThrownBy(() -> expired.use(m -> {}))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("expired");
  }

  @Test
  void concurrentUseClaimsExactlyOnce() throws InterruptedException {
    // P-1: the lock-free single-use claim must admit exactly one caller under contention.
    final var lease = lease("abcd".toCharArray(), () -> {});
    final int threads = 16;
    final var start = new CountDownLatch(1);
    final var done = new CountDownLatch(threads);
    final var successes = new AtomicInteger();
    for (int i = 0; i < threads; i++) {
      new Thread(
              () -> {
                try {
                  start.await();
                  lease.use(m -> {});
                  successes.incrementAndGet();
                } catch (final IllegalStateException alreadyUsed) {
                  // expected for all but one caller
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              })
          .start();
    }
    start.countDown();
    done.await();
    assertThat(successes.get()).isEqualTo(1);
    lease.close();
  }

  @Test
  void leaseIsNeverSerializable() {
    // Doc 26 §20.1 RED / §6: a lease must never be serializable.
    assertThat(Serializable.class.isAssignableFrom(SanitizableCredentialLease.class)).isFalse();
  }

  @Test
  void constructorRejectsNullArguments() {
    assertThatThrownBy(
            () ->
                new SanitizableCredentialLease(
                    null, TENANT, FAR_FUTURE, new char[1], () -> {}, CLOCK))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () -> new SanitizableCredentialLease("l", TENANT, FAR_FUTURE, null, () -> {}, CLOCK))
        .isInstanceOf(NullPointerException.class);
  }
}
