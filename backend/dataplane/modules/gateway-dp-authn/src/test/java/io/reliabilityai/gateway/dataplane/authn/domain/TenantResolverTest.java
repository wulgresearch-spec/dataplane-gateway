package io.reliabilityai.gateway.dataplane.authn.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for post-auth tenant resolution (Doc 37 §10/§TRF). */
class TenantResolverTest {

  private final TenantResolver resolver = new TenantResolver();

  private static TenantScopeSnapshot snapshot() {
    return new TenantScopeSnapshot(
        new SnapshotVersion("tenant-scope", "v1"),
        new Region("us-east-1"),
        Map.of("principal-1", TenantScope.of("org-1", "tenant-1")));
  }

  @Test
  void resolvesMappedPrincipal() {
    assertThat(resolver.resolve(snapshot(), new PrincipalId("principal-1")))
        .contains(TenantScope.of("org-1", "tenant-1"));
  }

  @Test
  void emptyForUnmappedPrincipal() {
    assertThat(resolver.resolve(snapshot(), new PrincipalId("unknown"))).isEmpty();
  }

  @Test
  void rejectsNullArguments() {
    assertThatThrownBy(() -> resolver.resolve(null, new PrincipalId("p")))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> resolver.resolve(snapshot(), null))
        .isInstanceOf(NullPointerException.class);
  }
}
