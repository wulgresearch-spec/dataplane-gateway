package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared deterministic inputs for the OpenAI adapter tests. */
final class OpenAiFixture {

  static final CanonicalModelId MODEL = new CanonicalModelId("gpt-4o-mini");
  static final PinnedVersion API_VERSION = new PinnedVersion("2024-10-01");

  private OpenAiFixture() {}

  static CanonicalRequest request(final Map<String, String> params) {
    return new CanonicalRequest(MODEL, List.of(new Message("user", "hello")), List.of(), params);
  }

  static CapabilityMapping capabilities(final Set<String> capabilities) {
    return new CapabilityMapping(
        MODEL, new SnapshotVersion("capabilities", "v1"), API_VERSION, capabilities);
  }

  /** A lease over fixed key material that records whether it was used and closed. */
  static final class TestLease implements CredentialLease {
    private final char[] material;
    private boolean closed;
    private int uses;

    TestLease(final String key) {
      this.material = key.toCharArray();
    }

    @Override
    public String leaseId() {
      return "lease-1";
    }

    @Override
    public TenantScope tenantScope() {
      return TenantScope.of("org-a", "tenant-a");
    }

    @Override
    public Instant notAfter() {
      return Instant.parse("2030-01-01T00:00:00Z");
    }

    @Override
    public boolean active() {
      return !closed;
    }

    @Override
    public void use(final SecretConsumer consumer) {
      uses++;
      consumer.accept(material);
    }

    @Override
    public void close() {
      closed = true;
    }

    int uses() {
      return uses;
    }
  }
}
