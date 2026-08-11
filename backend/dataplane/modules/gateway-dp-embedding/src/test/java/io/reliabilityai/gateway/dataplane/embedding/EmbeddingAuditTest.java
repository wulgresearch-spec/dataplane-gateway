package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingAuditPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingGovernancePort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingCache;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingPipeline;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingProviderRegistry;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingRetryPolicy;
import io.reliabilityai.gateway.dataplane.embedding.internal.InProcessEmbeddingMetrics;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the audit trail says happened, and whether that is what happened.
 *
 * <p>The audit record is the one output nothing downstream re-derives. A wrong latency shows up
 * next to a right one; a wrong audit line is simply believed. So these tests assert the
 * <em>counts</em> rather than merely that the port was called — an audit that reports every
 * attempted input as embedded is worse than no audit, because it is a compliance record stating
 * that a batch succeeded during an outage in which none of it did.
 *
 * <p>Content is asserted by its absence. The port takes counts, costs, a tenant, a provider and a
 * neutral model id, and nothing else; a trail quoting embedded text would be a second copy of
 * tenant data in the store most likely to be exported for review.
 */
@DisplayName("embedding audit")
final class EmbeddingAuditTest {

  private EmbeddingFixtures.TestClock clock;
  private InProcessEmbeddingMetrics metrics;
  private EmbeddingCache cache;
  private EmbeddingProviderRegistry registry;
  private RecordingAudit audit;

  @BeforeEach
  void setUp() {
    clock = new EmbeddingFixtures.TestClock();
    metrics = new InProcessEmbeddingMetrics();
    cache = new EmbeddingCache(clock, metrics, Duration.ofHours(1), Duration.ofMinutes(10), 1_000);
    registry = new EmbeddingProviderRegistry(EmbeddingFailurePolicy.defaults(), clock);
    audit = new RecordingAudit();
  }

  /** An audit sink that keeps what it was told. */
  private static final class RecordingAudit implements EmbeddingAuditPort {

    private final List<String> embedded = new ArrayList<>();
    private final List<String> refused = new ArrayList<>();

    @Override
    public void embedded(
        final TenantScope tenant,
        final ProviderId provider,
        final String modelId,
        final int inputs,
        final EmbeddingUsage usage) {
      embedded.add(
          tenant.org()
              + "/"
              + provider.value()
              + "/"
              + modelId
              + "/"
              + inputs
              + "/"
              + usage.costMicros());
    }

    @Override
    public void refused(
        final TenantScope tenant,
        final String modelId,
        final EmbeddingFailure reason,
        final int inputs) {
      refused.add(tenant.org() + "/" + modelId + "/" + reason + "/" + inputs);
    }
  }

  private EmbeddingPipeline pipelineOver(
      final EmbeddingFixtures.ScriptedProvider provider, final EmbeddingGovernancePort governance) {
    registry.register(provider);
    return new EmbeddingPipeline(
        registry, cache, governance, EmbeddingRetryPolicy.immediate(2), metrics, audit);
  }

  private static EmbeddingRequest request(final String... texts) {
    return new EmbeddingRequest(
        ACME, MODEL, List.of(texts), EmbeddingRequest.EmbeddingPurpose.WRITE);
  }

  @Test
  @DisplayName("a successful request is audited with the tenant, provider, model and count")
  void aSuccessfulRequestIsAuditedWithTheTenantProviderModelAndCount() {
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("alpha", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE);

    pipeline.embed(request("a", "b", "c"));

    assertThat(audit.embedded).containsExactly("acme/alpha/" + MODEL + "/3/7");
    assertThat(audit.refused).isEmpty();
  }

  @Test
  @DisplayName("a fully failed request audits no embeddings and one refusal")
  void aFullyFailedRequestAuditsNoEmbeddingsAndOneRefusal() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("alpha", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.AUTH_FAILED));
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    pipeline.embed(request("a", "b"));

    // The defect this test exists for: reporting misses-attempted as inputs-embedded told the
    // compliance record that two inputs were embedded during a failure in which none were.
    assertThat(audit.embedded).isEmpty();
    assertThat(audit.refused).containsExactly("acme/" + MODEL + "/AUTH_FAILED/2");
  }

  @Test
  @DisplayName("a partly failed request audits the successes and the refusals separately")
  void aPartlyFailedRequestAuditsTheSuccessesAndTheRefusalsSeparately() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("alpha", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.PartialFailure(Set.of(1)));
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    pipeline.embed(request("a", "b", "c"));

    assertThat(audit.embedded).containsExactly("acme/alpha/" + MODEL + "/2/7");
    assertThat(audit.refused).containsExactly("acme/" + MODEL + "/TOO_LARGE/1");
  }

  @Test
  @DisplayName("a governance refusal is audited before any provider is called")
  void aGovernanceRefusalIsAuditedBeforeAnyProviderIsCalled() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("alpha", clock, 64);
    final EmbeddingPipeline pipeline =
        pipelineOver(
            provider,
            (tenant, model, inputs, tokens, cost) -> EmbeddingGovernancePort.Decision.DENY);

    pipeline.embed(request("a", "b"));

    assertThat(provider.calls()).isZero();
    assertThat(audit.embedded).isEmpty();
    assertThat(audit.refused).containsExactly("acme/" + MODEL + "/BUDGET_EXCEEDED/2");
  }

  @Test
  @DisplayName("a request for an unserved model is audited as a refusal")
  void aRequestForAnUnservedModelIsAuditedAsARefusal() {
    pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("alpha", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE)
        .embed(
            new EmbeddingRequest(
                ACME, "no-such-model", List.of("a"), EmbeddingRequest.EmbeddingPurpose.WRITE));

    assertThat(audit.embedded).isEmpty();
    assertThat(audit.refused).containsExactly("acme/no-such-model/REJECTED/1");
  }

  @Test
  @DisplayName("a fully cached request is audited as nothing, because nothing was spent")
  void aFullyCachedRequestIsAuditedAsNothingBecauseNothingWasSpent() {
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("alpha", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE);
    pipeline.embed(request("repeated"));
    audit.embedded.clear();

    pipeline.embed(request("repeated"));

    // The audit trail records spend. A cache hit is not spend, and an entry claiming otherwise
    // would inflate every per-tenant usage report by the cache hit ratio.
    assertThat(audit.embedded).isEmpty();
    assertThat(audit.refused).isEmpty();
  }

  @Test
  @DisplayName("only the cache misses are audited when a request is partly cached")
  void onlyTheCacheMissesAreAuditedWhenARequestIsPartlyCached() {
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("alpha", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE);
    pipeline.embed(request("cached"));
    audit.embedded.clear();

    pipeline.embed(request("cached", "fresh"));

    assertThat(audit.embedded).containsExactly("acme/alpha/" + MODEL + "/1/7");
  }

  @Test
  @DisplayName("the no-op audit accepts every call, so a deployment may opt out")
  void theNoOpAuditAcceptsEveryCall() {
    final EmbeddingPipeline pipeline =
        new EmbeddingPipeline(
            registry,
            cache,
            EmbeddingGovernancePort.PERMISSIVE,
            EmbeddingRetryPolicy.immediate(2),
            metrics,
            EmbeddingAuditPort.NOOP);
    registry.register(new EmbeddingFixtures.ScriptedProvider("alpha", clock, 64));

    assertThat(pipeline.embed(request("a")).complete()).isTrue();
  }
}
