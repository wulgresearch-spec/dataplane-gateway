package io.reliabilityai.gateway.dataplane.memory.store.journal;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryContent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.RetrievalMode;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

/**
 * Builders for the adapter's tests.
 *
 * <p>Self-contained on purpose. Reaching into the memory runtime's own test fixtures would need a
 * test-jar dependency, and the adapter is supposed to be provable against the <em>published</em>
 * contract alone — if it needed C17's test internals to be testable, the port would not be a real
 * boundary.
 */
final class StoreFixtures {

  static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  static final TenantScope TENANT = TenantScope.of("acme", "core");
  static final TenantScope OTHER_TENANT = TenantScope.of("globex", "core");
  static final PrincipalId ALICE = new PrincipalId("alice");
  static final PrincipalId BOB = new PrincipalId("bob");

  private StoreFixtures() {
    throw new AssertionError("no instances");
  }

  /** A digest that matches what the runtime would compute, so integrity checks pass. */
  static String digestOf(final String body) {
    return io.reliabilityai.gateway.dataplane.memory.domain.MemoryDigest.of(body);
  }

  static MemoryRecord record(final String id, final MemoryScope scope, final String body) {
    return record(id, scope, MemoryType.LONG_TERM, body, Map.of(), null, T0);
  }

  static MemoryRecord record(
      final String id,
      final MemoryScope scope,
      final MemoryType type,
      final String body,
      final Map<String, String> metadata,
      final Instant expiresAt,
      final Instant createdAt) {
    return new MemoryRecord(
        MemoryRecordId.of(id),
        scope,
        type,
        MemoryContent.plain(body, digestOf(body)),
        DataClassification.INTERNAL,
        metadata,
        createdAt,
        Optional.ofNullable(expiresAt),
        1,
        0.5d,
        type.alwaysTainted(),
        false,
        false,
        false,
        Optional.empty(),
        0L);
  }

  /**
   * A ciphertext that does not contain its plaintext.
   *
   * <p>Not cryptography — the adapter never sees a key and never should. But a stand-in that
   * embedded the plaintext would make "the sealed body never reaches disk in the clear" untestable,
   * which the first version of this fixture did and the test duly caught.
   */
  static String fakeCipher(final String plaintext) {
    return "cipher:"
        + java.util.Base64.getEncoder()
            .encodeToString(
                new StringBuilder(plaintext)
                    .reverse()
                    .toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** A record whose body is sealed, to prove ciphertext round-trips and is never searched. */
  static MemoryRecord sealedRecord(
      final String id, final MemoryScope scope, final String plaintext) {
    return new MemoryRecord(
        MemoryRecordId.of(id),
        scope,
        MemoryType.LONG_TERM,
        MemoryContent.sealed(
            fakeCipher(plaintext), digestOf(plaintext), "key-ref-1", plaintext.length()),
        DataClassification.PII,
        Map.of(),
        T0,
        Optional.empty(),
        1,
        0.5d,
        false,
        false,
        false,
        false,
        Optional.empty(),
        0L);
  }

  /** A record exercising every awkward field at once, for the round-trip test. */
  static MemoryRecord awkwardRecord(final String id, final MemoryScope scope) {
    final String body = "line one\nline|two=three\\four\r\nunicode → ✓";
    return new MemoryRecord(
        MemoryRecordId.of(id),
        scope,
        MemoryType.EPISODIC,
        MemoryContent.plain(body, digestOf(body)),
        DataClassification.SENSITIVE_PII,
        Map.of("k|1", "v=1", "k\\2", "v\n2", "plain", "value"),
        T0.plusSeconds(37),
        Optional.of(T0.plus(Duration.ofDays(3))),
        7,
        0.875d,
        true,
        true,
        true,
        true,
        Optional.of(T0.plusSeconds(99)),
        42L);
  }

  static MemoryQuery scopeQuery(final MemoryScope scope, final MemoryType type) {
    return MemoryQuery.ofScope(scope, type);
  }

  static MemoryQuery keywordQuery(
      final MemoryScope scope, final MemoryType type, final String text) {
    return MemoryQuery.ofKeyword(scope, type, text);
  }

  static MemoryQuery limitedQuery(final MemoryScope scope, final MemoryType type, final int limit) {
    return new MemoryQuery(
        scope,
        EnumSet.of(type),
        RetrievalMode.SCOPE,
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Optional.empty(),
        limit,
        false);
  }

  static MemoryQuery metadataQuery(
      final MemoryScope scope, final MemoryType type, final Map<String, String> filters) {
    return new MemoryQuery(
        scope,
        EnumSet.of(type),
        RetrievalMode.METADATA,
        Optional.empty(),
        Optional.empty(),
        filters,
        Optional.empty(),
        Optional.empty(),
        20,
        false);
  }

  static MemoryQuery windowQuery(
      final MemoryScope scope, final MemoryType type, final Instant from, final Instant to) {
    return new MemoryQuery(
        scope,
        EnumSet.of(type),
        RetrievalMode.TIME,
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Optional.ofNullable(from),
        Optional.ofNullable(to),
        20,
        false);
  }

  static MemoryQuery archivedQuery(final MemoryScope scope, final MemoryType type) {
    return new MemoryQuery(
        scope,
        EnumSet.of(type),
        RetrievalMode.SCOPE,
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Optional.empty(),
        20,
        true);
  }
}
