package io.reliabilityai.gateway.dataplane.memory.pii;

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
import io.reliabilityai.gateway.dataplane.memory.api.PiiClassifierPort;
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
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryMemoryStore;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryVectorIndex;
import io.reliabilityai.gateway.dataplane.memory.internal.InProcessMemoryMetrics;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.dataplane.memory.internal.NotRealCryptoSealer;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Detection feeding governance, through the real C17 write pipeline.
 *
 * <p>The unit tests prove the engine classifies correctly. This proves the classification
 * <em>reaches the decision</em>: that governance is handed what the detector found, that a tenant
 * policy can act on it, and that C17 needed no change to make that true.
 *
 * <p><b>Not one line of C17 was modified.</b> {@code ConservativePiiClassifier} is replaced by
 * {@link RuleBasedPiiClassifier} — one constructor argument.
 */
@DisplayName("classification reaching governance")
final class PiiGovernanceIntegrationTest {

  private static final TenantScope TENANT = TenantScope.of("acme", "core");
  private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

  /** Records what governance was told, so the test can assert on the decision's input. */
  private static final class RecordingGovernance implements MemoryGovernancePort {

    private final List<DataClassification> seen = new ArrayList<>();
    private final Set<DataClassification> refused;

    RecordingGovernance(final Set<DataClassification> refused) {
      this.refused = refused;
    }

    @Override
    public Decision admitWrite(
        final PrincipalId principal,
        final MemoryScope scope,
        final MemoryType type,
        final DataClassification classification,
        final int sizeBytes) {
      seen.add(classification);
      return refused.contains(classification)
          ? new Decision(Verdict.DENY, "classification.refused")
          : Decision.ALLOWED;
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
  @DisplayName("governance is handed the classification the detector produced")
  void governanceIsHandedTheClassificationTheDetectorProduced() {
    final RecordingGovernance governance = new RecordingGovernance(Set.of());
    final MemoryRuntime runtime = runtime(governance, PiiAction.ALLOW, false);

    runtime.write(caller(), write("nothing sensitive in this sentence at all", "k1"));
    runtime.write(caller(), write("reach me at alice@example.com", "k2"));
    runtime.write(caller(), write("card 4111 1111 1111 1111 on file", "k3"));

    // The mission's premise was that PII_RESTRICTION depends on caller-supplied metadata. It does
    // not: MemoryWriteRequest has no classification field, and this is what governance actually
    // receives. What changes with B23 is the quality of that answer, not its provenance.
    assertThat(governance.seen)
        .containsExactly(
            DataClassification.INTERNAL, DataClassification.PII, DataClassification.SENSITIVE_PII);
  }

  @Test
  @DisplayName("a tenant policy can refuse storage on the strength of the classification")
  void aTenantPolicyCanRefuseStorageOnTheStrengthOfTheClassification() {
    final RecordingGovernance governance =
        new RecordingGovernance(Set.of(DataClassification.SENSITIVE_PII));
    final MemoryRuntime runtime = runtime(governance, PiiAction.ALLOW, false);

    assertThat(runtime.write(caller(), write("an ordinary note about the roadmap", "k1")))
        .isInstanceOf(MemoryOutcome.Written.class);
    assertThat(runtime.write(caller(), write("card 4111 1111 1111 1111 on file", "k2")))
        .isInstanceOf(MemoryOutcome.Refused.class);
  }

  @Test
  @DisplayName("a credential is refused outright because SECRET may never be stored")
  void aCredentialIsRefusedOutrightBecauseSecretMayNeverBeStored() {
    final RecordingGovernance governance = new RecordingGovernance(Set.of());
    final MemoryRuntime runtime = runtime(governance, PiiAction.ALLOW, false);

    final MemoryOutcome outcome =
        runtime.write(caller(), write("the deploy password = hunter2xyz please keep", "k1"));

    // C17 already refuses SECRET at MemoryWritePipeline:143. Before B23 nothing reliably produced
    // that classification; now an API key or password in a memory body stops the write. This is a
    // strengthening of existing behaviour rather than a new mechanism.
    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(governance.seen).isEmpty();
  }

  @Test
  @DisplayName("a policy requiring encryption seals what the detector classified as sensitive")
  void aPolicyRequiringEncryptionSealsWhatTheDetectorClassifiedAsSensitive() {
    final MemoryRuntime runtime = runtime(new RecordingGovernance(Set.of()), PiiAction.ALLOW, true);

    final MemoryOutcome outcome =
        runtime.write(caller(), write("card 4111 1111 1111 1111 on file", "k1"));

    assertThat(((MemoryOutcome.Written) outcome).sealed()).isTrue();
  }

  @Test
  @DisplayName("a redacting policy stores the masked body, not the original")
  void aRedactingPolicyStoresTheMaskedBodyNotTheOriginal() {
    final MemoryRuntime runtime =
        runtime(new RecordingGovernance(Set.of()), PiiAction.REDACT, false);

    final MemoryOutcome outcome =
        runtime.write(caller(), write("reach me at alice@example.com any time", "k1"));
    assertThat(((MemoryOutcome.Written) outcome).redacted()).isTrue();

    final MemoryOutcome read =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
    final String stored = ((MemoryOutcome.Retrieved) read).hits().get(0).record().content().body();

    // Masking is a by-product of knowing the spans, not a policy decision — PiiAction still decides
    // whether the mask is used. Producing it is nonetheless required: the port carries a redacted
    // body and C17 uses it, so returning the original unchanged would silently switch redaction
    // off.
    assertThat(stored).doesNotContain("alice@example.com").contains("[redacted]");
  }

  @Test
  @DisplayName("the port implementation is a drop-in for the reference classifier")
  void thePortImplementationIsADropInForTheReferenceClassifier() {
    final PiiClassifierPort port =
        new RuleBasedPiiClassifier(new PiiRuleRegistry(PiiMetricsPort.NOOP));

    // Nothing more than this is needed to adopt it, which is the whole test.
    assertThat(port.classify("alice@example.com").classification())
        .isEqualTo(DataClassification.PII);
    assertThat(port.classify("an ordinary sentence").classification())
        .isEqualTo(DataClassification.INTERNAL);
  }

  @Test
  @DisplayName("content with nothing found maps to INTERNAL rather than PUBLIC")
  void contentWithNothingFoundMapsToInternalRatherThanPublic() {
    final PiiClassifierPort port =
        new RuleBasedPiiClassifier(new PiiRuleRegistry(PiiMetricsPort.NOOP));

    // "No personal data detected" is not "safe to disclose". A strategy document contains neither,
    // and a detector finding nothing is also what a blind detector does. PUBLIC is a claim this
    // engine is not in a position to make.
    assertThat(port.classify("the merger closes in October").classification())
        .isEqualTo(DataClassification.INTERNAL);
  }

  /**
   * Assembles C17 over the rule-based classifier.
   *
   * @param governance the governance port
   * @param action what policy does about personal data
   * @param encryption whether policy requires sealing
   * @return the runtime
   */
  private static MemoryRuntime runtime(
      final MemoryGovernancePort governance, final PiiAction action, final boolean encryption) {
    final MemoryPolicyStore policies = new MemoryPolicyStore(4);
    policies.install(
        new MemoryPolicySnapshot(
            1L,
            Map.of(),
            Map.of(),
            new EffectiveMemoryPolicy(
                Optional.of(Duration.ofDays(30)),
                Optional.empty(),
                action,
                Set.of("eu-west-1"),
                encryption,
                false,
                true,
                Optional.empty(),
                VersioningMode.APPEND,
                Optional.empty(),
                DataClassification.SENSITIVE_PII,
                true)));
    final InMemoryMemoryStore store = new InMemoryMemoryStore();
    final InMemoryVectorIndex index = new InMemoryVectorIndex();
    final InProcessMemoryMetrics metrics = new InProcessMemoryMetrics();
    final ClockPort clock = () -> T0;
    final MemoryCryptoPort crypto = new NotRealCryptoSealer();
    final MemoryEmbedder embedder =
        new MemoryEmbedder(
            text -> {
              final float[] vector = new float[8];
              for (int i = 0; i < text.length(); i++) {
                vector[text.charAt(i) % 8] += 1.0f;
              }
              return vector;
            });
    final PiiClassifierPort classifier =
        new RuleBasedPiiClassifier(new PiiRuleRegistry(PiiMetricsPort.NOOP));
    return new MemoryRuntime(
        new MemoryWritePipeline(
            policies,
            governance,
            classifier,
            crypto,
            store,
            index,
            embedder,
            MemoryAuditPort.NOOP,
            metrics,
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
            metrics,
            clock),
        policies,
        governance,
        store,
        index,
        MemoryAuditPort.NOOP,
        metrics,
        clock);
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
}
