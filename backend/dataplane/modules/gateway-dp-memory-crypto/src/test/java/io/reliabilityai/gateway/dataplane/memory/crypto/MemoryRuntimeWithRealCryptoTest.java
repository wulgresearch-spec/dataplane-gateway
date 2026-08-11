package io.reliabilityai.gateway.dataplane.memory.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCaller;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryOutcome;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryWriteRequest;
import io.reliabilityai.gateway.dataplane.memory.api.PiiAction;
import io.reliabilityai.gateway.dataplane.memory.api.RankingSignal;
import io.reliabilityai.gateway.dataplane.memory.api.RetrievalMode;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryEmbedder;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryIdFactory;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryReadPipeline;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryRuntime;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryWritePipeline;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy.VersioningMode;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryRanker;
import io.reliabilityai.gateway.dataplane.memory.internal.ConservativePiiClassifier;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryVectorIndex;
import io.reliabilityai.gateway.dataplane.memory.internal.InProcessMemoryMetrics;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.dataplane.memory.store.journal.JournalMemoryStore;
import io.reliabilityai.gateway.ports.ClockPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole Memory Runtime, over the durable journal, with real cryptography underneath.
 *
 * <p>The unit tests prove the cipher is correct in isolation. This proves it is correct <em>in
 * place</em>: that C17 needed no change to use it, that what lands on disk is unreadable, and that
 * a key rotation is survivable by a system that is already holding data.
 *
 * <p><b>Not one line of C17 was modified.</b> The runtime is assembled exactly as AD-026 specifies,
 * with {@code NotRealCryptoSealer} replaced by {@link AeadMemoryCrypto} — one constructor argument.
 */
@DisplayName("Memory Runtime over durable storage with production cryptography")
final class MemoryRuntimeWithRealCryptoTest {

  private static final TenantScope TENANT = TenantScope.of("acme", "core");
  private static final Instant T0 = Instant.parse("2026-07-01T00:00:00Z");

  private final InProcessMemoryMetrics memoryMetrics = new InProcessMemoryMetrics();
  private final InMemoryVectorIndex index = new InMemoryVectorIndex();

  /** A clock frozen at T0; nothing here depends on time passing. */
  private static final class FixedClock implements ClockPort {
    @Override
    public Instant now() {
      return T0;
    }
  }

  /**
   * Governance that permits everything, so this test is about cryptography and not about policy.
   */
  private static final class PermissiveGovernance implements MemoryGovernancePort {
    @Override
    public Decision admitWrite(
        final PrincipalId principal,
        final MemoryScope scope,
        final MemoryType type,
        final DataClassification classification,
        final int sizeBytes) {
      return Decision.ALLOWED;
    }

    @Override
    public Decision admitRead(
        final PrincipalId principal,
        final MemoryScope scope,
        final Set<MemoryType> types,
        final RetrievalMode mode) {
      return Decision.ALLOWED;
    }

    @Override
    public Decision admitDelete(
        final PrincipalId principal, final MemoryScope scope, final MemoryType type) {
      return Decision.ALLOWED;
    }
  }

  @Test
  @DisplayName("a sealed memory round-trips through the full pipeline")
  void aSealedMemoryRoundTripsThroughTheFullPipeline(@TempDir final Path root) throws Exception {
    final Path keyring = keyring(root, "2026-07");
    try (JournalMemoryStore store = new JournalMemoryStore(root.resolve("journal"))) {
      final MemoryRuntime runtime = runtimeOver(store, crypto(keyring));

      assertThat(runtime.write(caller(), write("the merger closes in October")))
          .isInstanceOf(MemoryOutcome.Written.class);

      final MemoryOutcome read = runtime.read(caller(), scopeQuery());
      assertThat(read).isInstanceOf(MemoryOutcome.Retrieved.class);
      final MemoryOutcome.Retrieved retrieved = (MemoryOutcome.Retrieved) read;
      assertThat(retrieved.hits()).hasSize(1);
      assertThat(retrieved.hits().get(0).record().content().body())
          .isEqualTo("the merger closes in October");
    }
  }

  @Test
  @DisplayName("the write pipeline records the memory as sealed")
  void theWritePipelineRecordsTheMemoryAsSealed(@TempDir final Path root) throws Exception {
    final Path keyring = keyring(root, "2026-07");
    try (JournalMemoryStore store = new JournalMemoryStore(root.resolve("journal"))) {
      final MemoryOutcome outcome =
          runtimeOver(store, crypto(keyring)).write(caller(), write("board minutes"));

      assertThat(((MemoryOutcome.Written) outcome).sealed()).isTrue();
    }
  }

  @Test
  @DisplayName("a stolen journal file yields no plaintext")
  void aStolenJournalFileYieldsNoPlaintext(@TempDir final Path root) throws Exception {
    final Path keyring = keyring(root, "2026-07");
    final Path journal = root.resolve("journal");
    final List<String> bodies =
        List.of(
            "the acquisition price is 4200000000",
            "candidate rejected for reasons of temperament",
            "root credentials rotate every Tuesday");
    try (JournalMemoryStore store = new JournalMemoryStore(journal)) {
      final MemoryRuntime runtime = runtimeOver(store, crypto(keyring));
      for (int i = 0; i < bodies.size(); i++) {
        runtime.write(caller(), write(bodies.get(i), "k" + i));
      }
      store.flush();
    }

    // Everything on disk, concatenated, as an attacker who took the volume would see it. The
    // keyring
    // sits in the same TempDir but is not part of the journal directory, matching a deployment
    // where
    // keys live outside the data volume — which is the only arrangement in which this test means
    // anything at all.
    final String onDisk = readEverything(journal);
    for (final String body : bodies) {
      assertThat(onDisk).doesNotContain(body);
      for (final String word : body.split(" ")) {
        if (word.length() >= 6) {
          assertThat(onDisk.toLowerCase(Locale.ROOT))
              .as("the word '%s' survived to disk", word)
              .doesNotContain(word.toLowerCase(Locale.ROOT));
        }
      }
    }
  }

  @Test
  @DisplayName("sealed memories survive a restart of both the store and the cipher")
  void sealedMemoriesSurviveARestartOfBothTheStoreAndTheCipher(@TempDir final Path root)
      throws Exception {
    final Path keyring = keyring(root, "2026-07");
    final Path journal = root.resolve("journal");
    try (JournalMemoryStore store = new JournalMemoryStore(journal)) {
      final MemoryRuntime runtime = runtimeOver(store, crypto(keyring));
      for (int i = 0; i < 20; i++) {
        runtime.write(caller(), write("durable body number " + i, "k" + i));
      }
    }

    // New store, new cipher, new provider — everything reconstructed from the keyring and the disk.
    try (JournalMemoryStore reopened = new JournalMemoryStore(journal)) {
      final MemoryOutcome read =
          runtimeOver(reopened, crypto(keyring)).read(caller(), scopeQuery());

      assertThat(((MemoryOutcome.Retrieved) read).hits()).hasSize(20);
      assertThat(((MemoryOutcome.Retrieved) read).hits())
          .allSatisfy(hit -> assertThat(hit.record().content().body()).startsWith("durable body"));
    }
  }

  @Test
  @DisplayName("memories written before a rotation still read afterwards")
  void memoriesWrittenBeforeARotationStillReadAfterwards(@TempDir final Path root)
      throws Exception {
    final Path keyring = root.resolve("keys");
    final String january = randomKey();
    final String july = randomKey();
    Files.writeString(keyring, "primary = 2026-01\n2026-01 = " + january + "\n");
    final Path journal = root.resolve("journal");
    try (JournalMemoryStore store = new JournalMemoryStore(journal)) {
      runtimeOver(store, crypto(keyring)).write(caller(), write("written under the old key"));
    }

    Files.writeString(
        keyring, "primary = 2026-07\n2026-01 = " + january + "\n2026-07 = " + july + "\n");
    try (JournalMemoryStore reopened = new JournalMemoryStore(journal)) {
      final MemoryRuntime runtime = runtimeOver(reopened, crypto(keyring));
      runtime.write(caller(), write("written under the new key", "k2"));

      final MemoryOutcome read = runtime.read(caller(), scopeQuery());
      assertThat(((MemoryOutcome.Retrieved) read).hits()).hasSize(2);
      assertThat(((MemoryOutcome.Retrieved) read).hits())
          .extracting(hit -> hit.record().content().body())
          .containsExactlyInAnyOrder("written under the old key", "written under the new key");
    }
  }

  @Test
  @DisplayName("a destroyed key leaves the record undecryptable rather than failing the read")
  void aDestroyedKeyLeavesTheRecordUndecryptableRatherThanFailingTheRead(@TempDir final Path root)
      throws Exception {
    final Path keyring = root.resolve("keys");
    final String january = randomKey();
    final String july = randomKey();
    Files.writeString(keyring, "primary = 2026-01\n2026-01 = " + january + "\n");
    final Path journal = root.resolve("journal");
    try (JournalMemoryStore store = new JournalMemoryStore(journal)) {
      runtimeOver(store, crypto(keyring))
          .write(caller(), write("content whose key will be destroyed"));
    }

    // The January key is gone from the keyring entirely — the crypto-shredding erasure story.
    Files.writeString(keyring, "primary = 2026-07\n2026-07 = " + july + "\n");
    try (JournalMemoryStore reopened = new JournalMemoryStore(journal)) {
      final MemoryOutcome read =
          runtimeOver(reopened, crypto(keyring)).read(caller(), scopeQuery());

      // AD-026 §11: a record that cannot be opened comes back marked undecryptable. Losing the
      // whole
      // read because one record's key was retired would make key destruction unusable in practice.
      assertThat(read).isInstanceOf(MemoryOutcome.Retrieved.class);
      final MemoryOutcome.Retrieved retrieved = (MemoryOutcome.Retrieved) read;
      assertThat(retrieved.hits()).hasSize(1);
      assertThat(retrieved.hits().get(0).record().content().readable()).isFalse();
      assertThat(retrieved.hits().get(0).record().content().body())
          .doesNotContain("content whose key will be destroyed");
    }
  }

  @Test
  @DisplayName("the cipher refuses a foreign tenant's record even when storage hands it over")
  void theCipherRefusesAForeignTenantsRecordEvenWhenStorageHandsItOver(@TempDir final Path root)
      throws Exception {
    final Path keyring = keyring(root, "2026-07");
    final AeadMemoryCrypto cipher = crypto(keyring);
    final MemoryScope acme = MemoryScope.ofTenant(TENANT);
    final MemoryScope globex = MemoryScope.ofTenant(TenantScope.of("globex", "core"));
    final MemoryCryptoPort.Sealed sealed = cipher.seal(acme, "acme confidential");

    // This is the defence-in-depth claim made concrete. The AD-027 sabotage run showed that
    // removing
    // the store's visibility filter is a two-line edit the compiler accepts. If that ever happens,
    // the tag still stands between one tenant and another's content.
    assertThat(cipher.unseal(globex, sealed.ciphertext(), sealed.keyRef())).isEmpty();
    assertThat(cipher.unseal(acme, sealed.ciphertext(), sealed.keyRef()))
        .contains("acme confidential");
  }

  @Test
  @DisplayName("keyword retrieval cannot match sealed content, and that is a finding")
  void keywordRetrievalCannotMatchSealedContentAndThatIsAFinding(@TempDir final Path root)
      throws Exception {
    final Path keyring = keyring(root, "2026-07");
    try (JournalMemoryStore store = new JournalMemoryStore(root.resolve("journal"))) {
      final MemoryRuntime runtime = runtimeOver(store, crypto(keyring));
      runtime.write(caller(), write("the merger closes in October"));

      final MemoryOutcome byKeyword = runtime.read(caller(), query("merger"));
      final MemoryOutcome byScope = runtime.read(caller(), scopeQuery());

      // Once a policy seals content, the store holds ciphertext, and keyword matching runs against
      // that ciphertext. The word is simply not there to find. This is not a defect introduced by
      // B24 — it is what encrypting a searchable field costs, and it is true of every store that is
      // not doing searchable encryption. It is recorded here rather than in prose because a silent
      // "zero results" is the kind of thing an operator discovers in production.
      //
      // Scope, metadata and time retrieval are unaffected. Semantic retrieval is unaffected because
      // the embedding is computed from plaintext before sealing.
      assertThat(((MemoryOutcome.Retrieved) byKeyword).hits()).isEmpty();
      assertThat(((MemoryOutcome.Retrieved) byScope).hits()).hasSize(1);
      assertThat(((MemoryOutcome.Retrieved) byScope).hits().get(0).record().content().body())
          .isEqualTo("the merger closes in October");
    }
  }

  /**
   * Assembles the runtime over a store and a cipher.
   *
   * @param store the durable store
   * @param crypto the cipher
   * @return the runtime
   */
  private MemoryRuntime runtimeOver(final JournalMemoryStore store, final MemoryCryptoPort crypto) {
    final MemoryPolicyStore policies = new MemoryPolicyStore(4);
    policies.install(new MemoryPolicySnapshot(1L, Map.of(), Map.of(), sealingPolicy()));
    final MemoryEmbedder embedder =
        new MemoryEmbedder(
            text -> {
              final float[] vector = new float[8];
              for (int i = 0; i < text.length(); i++) {
                vector[text.charAt(i) % 8] += 1.0f;
              }
              return vector;
            });
    final MemoryGovernancePort governance = new PermissiveGovernance();
    final ClockPort clock = new FixedClock();
    return new MemoryRuntime(
        new MemoryWritePipeline(
            policies,
            governance,
            new ConservativePiiClassifier(),
            crypto,
            store,
            index,
            embedder,
            MemoryAuditPort.NOOP,
            memoryMetrics,
            clock,
            MemoryIdFactory.DETERMINISTIC),
        new MemoryReadPipeline(
            policies,
            governance,
            store,
            index,
            embedder,
            crypto,
            new MemoryRanker(RankingSignal.Weights.DEFAULT),
            MemoryAuditPort.NOOP,
            memoryMetrics,
            clock),
        policies,
        governance,
        store,
        index,
        MemoryAuditPort.NOOP,
        memoryMetrics,
        clock);
  }

  /**
   * A policy that requires content to be sealed.
   *
   * @return the policy
   */
  private static EffectiveMemoryPolicy sealingPolicy() {
    return new EffectiveMemoryPolicy(
        Optional.of(Duration.ofDays(30)),
        Optional.empty(),
        PiiAction.REDACT,
        Set.of("eu-west-1"),
        true,
        false,
        true,
        Optional.empty(),
        VersioningMode.APPEND,
        Optional.empty(),
        DataClassification.SENSITIVE_PII,
        true);
  }

  /**
   * Builds a cipher over a keyring file.
   *
   * @param keyring the keyring path
   * @return the cipher
   */
  private static AeadMemoryCrypto crypto(final Path keyring) {
    return new AeadMemoryCrypto(new FileMasterKeyProvider(keyring), new InProcessCryptoMetrics());
  }

  /**
   * Writes a keyring holding one generated version.
   *
   * @param root where to put it
   * @param version the version label
   * @return the keyring path
   * @throws IOException when it cannot be written
   */
  private static Path keyring(final Path root, final String version) throws IOException {
    final Path keyring = root.resolve("keys");
    Files.writeString(
        keyring, "primary = " + version + "\n" + version + " = " + randomKey() + "\n");
    return keyring;
  }

  /**
   * Generates a base64 AES-256 key.
   *
   * @return the encoded key
   */
  private static String randomKey() {
    final byte[] material = new byte[32];
    new java.security.SecureRandom().nextBytes(material);
    return Base64.getEncoder().encodeToString(material);
  }

  /**
   * Reads every byte under a directory as one string.
   *
   * @param directory the root
   * @return the concatenated contents
   * @throws IOException when a file cannot be read
   */
  private static String readEverything(final Path directory) throws IOException {
    final StringBuilder all = new StringBuilder();
    try (Stream<Path> files = Files.walk(directory)) {
      for (final Path file : files.filter(Files::isRegularFile).toList()) {
        all.append(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1));
      }
    }
    return all.toString();
  }

  /**
   * The caller used throughout.
   *
   * @return a tenant-scoped caller
   */
  private static MemoryCaller caller() {
    return MemoryCaller.of(
        new PrincipalId("p-1"), MemoryScope.ofTenant(TENANT), new CorrelationId("corr-1"));
  }

  /**
   * A write request with a default idempotency key.
   *
   * @param body the content
   * @return the request
   */
  private static MemoryWriteRequest write(final String body) {
    return write(body, "k1");
  }

  /**
   * A write request.
   *
   * @param body the content
   * @param key the idempotency key
   * @return the request
   */
  private static MemoryWriteRequest write(final String body, final String key) {
    return MemoryWriteRequest.of(
        MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, body, key, "eu-west-1");
  }

  /**
   * A scope query.
   *
   * <p>Scope rather than keyword, and that is not a stylistic choice: see {@link
   * #keywordRetrievalCannotMatchSealedContentAndThatIsAFinding}. Once content is sealed, the store
   * holds ciphertext, and no keyword in the plaintext can be matched against it.
   *
   * @return the query
   */
  private static MemoryQuery scopeQuery() {
    return MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM);
  }

  /**
   * A keyword query.
   *
   * @param term the keyword
   * @return the query
   */
  private static MemoryQuery query(final String term) {
    return MemoryQuery.ofKeyword(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, term);
  }
}
