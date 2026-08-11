package io.reliabilityai.gateway.dataplane.app.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.io.ToolDefinition;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests the three admission seams individually: token bounding, scope resolution, legacy
 * adaptation.
 */
class AdmissionSeamsTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final ClockPort CLOCK = () -> NOW;
  private static final CanonicalModelId MODEL = new CanonicalModelId("model.test.v1");

  // ---- the token bound ------------------------------------------------------------------------

  private static CanonicalRequest request(
      final List<Message> messages, final List<ToolDefinition> tools) {
    return new CanonicalRequest(MODEL, messages, tools, Map.of());
  }

  @Test
  void theBoundCountsRoleAndContentCharacters() {
    final CharacterBoundTokenEstimator estimator = new CharacterBoundTokenEstimator(0L);

    assertThat(estimator.promptTokens(request(List.of(new Message("user", "hello")), List.of())))
        .hasValue(9L);
  }

  @Test
  void theBoundAddsThePerMessageOverheadOncePerMessage() {
    final CharacterBoundTokenEstimator estimator = new CharacterBoundTokenEstimator(8L);

    assertThat(
            estimator.promptTokens(
                request(List.of(new Message("user", "a"), new Message("user", "b")), List.of())))
        .hasValue(2L * (4L + 1L + 8L));
  }

  @Test
  void theBoundCountsToolSchemas() {
    final CharacterBoundTokenEstimator estimator = new CharacterBoundTokenEstimator(0L);
    final String schema = "{\"k\":\"" + "v".repeat(100) + "\"}";

    // A tiny prompt with a large schema is still a large request; ignoring schemas would
    // under-count
    // exactly the requests most likely to exceed a ceiling.
    assertThat(estimator.promptTokens(request(List.of(), List.of(new ToolDefinition("t", schema)))))
        .hasValue(1L + schema.length());
  }

  @Test
  void anEmptyRequestBoundsToZeroWhichIsAKnownAnswerNotAnUnknownOne() {
    assertThat(new CharacterBoundTokenEstimator(0L).promptTokens(request(List.of(), List.of())))
        .hasValue(0L);
  }

  @Test
  void theBoundIsNeverBelowTheCharacterCount() {
    final CharacterBoundTokenEstimator estimator = new CharacterBoundTokenEstimator(0L);
    final String content = "The quick brown fox jumps over the lazy dog.";

    // The soundness argument for this estimator: every tokenizer consumes at least one character
    // per
    // token, so characters can never be fewer than tokens.
    assertThat(estimator.promptTokens(request(List.of(new Message("user", content)), List.of())))
        .hasValue((long) content.length() + 4L);
  }

  @Test
  void theUnknownEstimatorAnswersNothing() {
    assertThat(PromptTokenEstimator.UNKNOWN.promptTokens(request(List.of(), List.of()))).isEmpty();
  }

  // ---- scope resolution -----------------------------------------------------------------------

  private static PrincipalContext principal(final Map<String, String> claims) {
    return new PrincipalContext(new PrincipalId("principal-a"), claims, "jws", "auth-1");
  }

  private static TenantContext tenant(final TenantScope scope) {
    return new TenantContext(scope);
  }

  @Test
  void tenantOnlyResolutionFollowsTheAuthenticatedHierarchy() {
    final ScopeChain chain =
        ClaimBasedScopeResolver.tenantOnly()
            .resolve(principal(Map.of()), tenant(new TenantScope("o", "t", "w", "p")));

    assertThat(chain.refs())
        .extracting(PolicyScopeRef::scope)
        .containsExactly(
            PolicyScope.GLOBAL,
            PolicyScope.ORGANIZATION,
            PolicyScope.WORKSPACE,
            PolicyScope.PROJECT);
  }

  @Test
  void anUnpopulatedTenantLevelIsSimplyAbsent() {
    final ScopeChain chain =
        ClaimBasedScopeResolver.tenantOnly()
            .resolve(principal(Map.of()), tenant(TenantScope.of("o", "t")));

    assertThat(chain.refs())
        .extracting(PolicyScopeRef::scope)
        .containsExactly(PolicyScope.GLOBAL, PolicyScope.ORGANIZATION);
  }

  @Test
  void theEnvironmentNodeComesFromTheNodesOwnConfiguration() {
    final ScopeChain chain =
        new ClaimBasedScopeResolver("", "", Optional.of("prod"))
            .resolve(principal(Map.of()), tenant(TenantScope.of("o", "t")));

    assertThat(chain.refs()).extracting(PolicyScopeRef::id).contains("prod");
  }

  @Test
  void theApiKeyNodeComesFromTheOperatorNamedClaim() {
    final ScopeChain chain =
        new ClaimBasedScopeResolver("kid", "", Optional.empty())
            .resolve(principal(Map.of("kid", "key-7")), tenant(TenantScope.of("o", "t")));

    assertThat(chain.refs())
        .anySatisfy(
            ref -> {
              assertThat(ref.scope()).isEqualTo(PolicyScope.API_KEY);
              assertThat(ref.id()).isEqualTo("key-7");
            });
  }

  @Test
  void anUnassertedApiKeyClaimOmitsTheNodeRatherThanInventingOne() {
    final ScopeChain chain =
        new ClaimBasedScopeResolver("kid", "", Optional.empty())
            .resolve(principal(Map.of()), tenant(TenantScope.of("o", "t")));

    // Inventing a placeholder id would attach every unkeyed request in the fleet to one shared
    // node,
    // letting one tenant's key policy govern another's traffic.
    assertThat(chain.refs()).extracting(PolicyScopeRef::scope).doesNotContain(PolicyScope.API_KEY);
  }

  @Test
  void aBlankClaimValueIsTreatedAsUnasserted() {
    final ScopeChain chain =
        new ClaimBasedScopeResolver("kid", "", Optional.empty())
            .resolve(principal(Map.of("kid", "  ")), tenant(TenantScope.of("o", "t")));

    assertThat(chain.refs()).extracting(PolicyScopeRef::scope).doesNotContain(PolicyScope.API_KEY);
  }

  @Test
  void aHumanPrincipalHangsOffTheUserNode() {
    final ScopeChain chain =
        new ClaimBasedScopeResolver("", "typ", Optional.empty())
            .resolve(principal(Map.of("typ", "human")), tenant(TenantScope.of("o", "t")));

    assertThat(chain.refs()).extracting(PolicyScopeRef::scope).contains(PolicyScope.USER);
  }

  @Test
  void aMachinePrincipalHangsOffTheServiceAccountNode() {
    final ScopeChain chain =
        new ClaimBasedScopeResolver("", "typ", Optional.empty())
            .resolve(
                principal(Map.of("typ", ClaimBasedScopeResolver.SERVICE_ACCOUNT_MARKER)),
                tenant(TenantScope.of("o", "t")));

    assertThat(chain.refs())
        .extracting(PolicyScopeRef::scope)
        .contains(PolicyScope.SERVICE_ACCOUNT);
  }

  @Test
  void anUnclassifiedPrincipalGetsNoPrincipalNodeAtAll() {
    final ScopeChain chain =
        new ClaimBasedScopeResolver("", "typ", Optional.empty())
            .resolve(principal(Map.of()), tenant(TenantScope.of("o", "t")));

    // Guessing human-or-machine would be wrong the first time an operator issues a JWT to a batch
    // job.
    assertThat(chain.refs())
        .extracting(PolicyScopeRef::scope)
        .doesNotContain(PolicyScope.USER, PolicyScope.SERVICE_ACCOUNT);
  }

  @Test
  void aFullyPopulatedResolutionWalksEveryLevel() {
    final ScopeChain chain =
        new ClaimBasedScopeResolver("kid", "typ", Optional.of("prod"))
            .resolve(
                principal(Map.of("kid", "key-1", "typ", "human")),
                tenant(new TenantScope("o", "t", "w", "p")));

    assertThat(chain.refs())
        .extracting(PolicyScopeRef::scope)
        .containsExactly(
            PolicyScope.GLOBAL,
            PolicyScope.ORGANIZATION,
            PolicyScope.WORKSPACE,
            PolicyScope.PROJECT,
            PolicyScope.ENVIRONMENT,
            PolicyScope.API_KEY,
            PolicyScope.USER);
  }

  // ---- the legacy adapter ---------------------------------------------------------------------

  private static PolicyRequest anyRequest() {
    return PolicyRequest.builder()
        .requestContext(
            new RequestContext(
                new CorrelationId("corr-1"),
                new IdempotencyKey("idem-1"),
                new CausationId("cause-1"),
                "traceparent",
                new Region("us-east-1")))
        .principal(principal(Map.of()))
        .tenant(tenant(TenantScope.of("o", "t")))
        .scopeChain(ScopeChain.forTenant(TenantScope.of("o", "t")))
        .region(new Region("us-east-1"))
        .build();
  }

  @Test
  void aLegacyPermitBecomesAnAllow() {
    final PolicyDecision decision =
        new LegacyGovernanceAdmission(
                (rc, p, t) -> new GovernanceDecision(true, List.of(), "permitted"), CLOCK)
            .admit(anyRequest());

    assertThat(decision.verdict()).isEqualTo(Verdict.ALLOW);
    assertThat(decision.admits()).isTrue();
  }

  @Test
  void aLegacyDenialBecomesADenyCarryingItsReason() {
    final PolicyDecision decision =
        new LegacyGovernanceAdmission(
                (rc, p, t) -> new GovernanceDecision(false, List.of(), "tenant-disabled"), CLOCK)
            .admit(anyRequest());

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
    assertThat(decision.reasonCode()).isEqualTo("tenant-disabled");
  }

  @Test
  void aLegacyPermitClaimsNoResolvedScopeItDidNotActuallyResolve() {
    final PolicyDecision decision =
        new LegacyGovernanceAdmission(
                (rc, p, t) -> new GovernanceDecision(true, List.of(), "permitted"), CLOCK)
            .admit(anyRequest());

    // Fabricating constraints here would hand the Router policy nobody authored, and would make a
    // narrower legacy permit look like a fully-governed one.
    assertThat(decision.context().allowedProviders()).isEmpty();
    assertThat(decision.context().residencyScope()).isEmpty();
    assertThat(decision.violations()).isEmpty();
  }

  @Test
  void aLegacyPortThatThrowsRefusesRatherThanPropagating() {
    final PolicyDecision decision =
        new LegacyGovernanceAdmission(
                (rc, p, t) -> {
                  throw new IllegalStateException("enforcement point down");
                },
                CLOCK)
            .admit(anyRequest());

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
    assertThat(decision.reasonCode()).isEqualTo("policy-unavailable");
  }

  @Test
  void aLegacyPortReturningNullRefuses() {
    final PolicyDecision decision =
        new LegacyGovernanceAdmission((rc, p, t) -> null, CLOCK).admit(anyRequest());

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void aBrokenClockDoesNotStopTheAdapterDeciding() {
    final PolicyDecision decision =
        new LegacyGovernanceAdmission(
                (rc, p, t) -> new GovernanceDecision(true, List.of(), "permitted"),
                () -> {
                  throw new IllegalStateException("clock down");
                })
            .admit(anyRequest());

    assertThat(decision.verdict()).isEqualTo(Verdict.ALLOW);
  }
}
