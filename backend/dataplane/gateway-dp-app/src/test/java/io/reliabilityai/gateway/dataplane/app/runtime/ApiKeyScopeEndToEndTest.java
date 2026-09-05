package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKey;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.binding.CharacterBoundTokenEstimator;
import io.reliabilityai.gateway.dataplane.app.binding.ClaimBasedScopeResolver;
import io.reliabilityai.gateway.dataplane.app.binding.GovernanceScopeResolver;
import io.reliabilityai.gateway.dataplane.app.pipeline.Operation;
import io.reliabilityai.gateway.dataplane.app.pipeline.PipelineOutcome;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.auth.jwt.JwtAuthenticationConfig;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole chain, on the live request path: a real signed JWT carries an API-key claim, real
 * authentication forwards it, the real resolver hangs an {@code API_KEY} node off it, and a policy
 * document authored against <em>that</em> node changes what the pipeline decides.
 *
 * <p>Every earlier test in this area proved one link. The verifier suite proved a declared claim
 * survives verification; the resolver suite proved a claim becomes a node; the engine suite proved
 * a node's policy is folded. None proved the links are joined, and a chain of proven links is not a
 * proven chain — the defect this closes was precisely a break <em>between</em> two
 * individually-correct components.
 *
 * <p>The decisive test is the pair. One request is refused because a document is attached to the
 * key it presents; the identical request is admitted when the same document is attached to a
 * different key. Nothing but the authenticated API-key identity differs, so the refusal cannot be
 * explained by the request, the model, the tenant, or the policy merely existing.
 */
class ApiKeyScopeEndToEndTest {

  private static final String ISSUER = "https://issuer.example";
  private static final String AUDIENCE = "gateway";
  private static final String TENANT_CLAIM = "tid";
  private static final String API_KEY_CLAIM = "akid";
  private static final String PRESENTED_KEY = "K123";
  private static final String OTHER_KEY = "K999";
  private static final String KID = "kid-1";
  private static final String PRINCIPAL = "principal-a";
  private static final String ORG = "org-a";
  private static final Region REGION = new Region("us-east-1");
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private final KeyPair keyPair = rsaKeyPair();
  private final AtomicReference<PolicySourcePort.PolicyBundle> published = new AtomicReference<>();

  /** The resolver under test; swapped by the custom-resolver case. */
  private final AtomicReference<GovernanceScopeResolver> resolver =
      new AtomicReference<>(new ClaimBasedScopeResolver(API_KEY_CLAIM, "", Optional.empty()));

  /** The claim name the minted token carries, kept in step with the resolver's declaration. */
  private final AtomicReference<String> mintedClaim = new AtomicReference<>(API_KEY_CLAIM);

  /** The value that claim carries; the principal-type cases need a marker, not a key id. */
  private final AtomicReference<String> mintedValue = new AtomicReference<>(PRESENTED_KEY);

  private GatewayRuntime gateway;

  @AfterEach
  void tearDown() {
    if (gateway != null && gateway.state() == GatewayRuntime.State.READY) {
      gateway.stop();
    }
  }

  private static KeyPair rsaKeyPair() {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (final Exception e) {
      throw new IllegalStateException("cannot generate key", e);
    }
  }

  /** The key snapshot the node verifies against — carrying this test's actual public key. */
  private VerificationKeySnapshot keySnapshot() {
    return new VerificationKeySnapshot(
        new SnapshotVersion("verification-keys", "v1"),
        REGION,
        List.of(
            new VerificationKey(
                KID,
                JwsAlgorithm.RS256,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()))),
        List.of());
  }

  /** A signed token for {@code principal-a}, optionally carrying the declared API-key claim. */
  private String token(final boolean withApiKeyClaim) {
    final String quote = "\"";
    final String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KID + "\"}");
    final String apiKey =
        withApiKeyClaim ? ",\"" + mintedClaim.get() + "\":\"" + mintedValue.get() + quote : "";
    final String payload =
        base64(
            "{\"iss\":\""
                + ISSUER
                + "\",\"aud\":\""
                + AUDIENCE
                + "\",\"sub\":\"principal-a\",\""
                + TENANT_CLAIM
                + "\":\"tenant-a\",\"exp\":"
                + NOW.plusSeconds(3600).getEpochSecond()
                + apiKey
                + "}");
    final String signingInput = header + "." + payload;
    try {
      final Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(keyPair.getPrivate());
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput
          + "."
          + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    } catch (final Exception e) {
      throw new IllegalStateException("cannot sign", e);
    }
  }

  private static String base64(final String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Publishes one document at the given API-key node and starts a node enforcing it. */
  private GatewayRuntime runtimeEnforcingApiKeyPolicy(final String keyId, final PolicyRule rule) {
    published.set(
        new PolicySourcePort.PolicyBundle(
            PolicyVersion.of("v1", 1L),
            List.of(
                GovernancePolicy.of(
                    "api-key-doc",
                    PolicyScopeRef.of(PolicyScope.API_KEY, keyId),
                    PolicyVersion.of("v1", 1L),
                    List.of(rule)))));
    gateway = new GatewayRuntime(config());
    gateway.start();
    return gateway;
  }

  private GatewayRuntimeConfig config() {
    final ExternalAdapters all = RuntimeFixture.allExternalAdapters();
    // No external verifier: AUTHN must bind the runtime's own JWT verifier, the component whose
    // claim forwarding is under test.
    final ExternalAdapters withoutVerifier =
        new ExternalAdapters(
            all.ingress(),
            Optional.empty(),
            all.governance(),
            all.providerTransport(),
            all.providerTranslator(),
            all.credentialPort(),
            all.capabilitySnapshot(),
            all.schemaValidator(),
            all.providerGeneration());

    final GatewayRuntimeConfig base =
        RuntimeFixture.config(
            RuntimeFixture.masterKey(),
            walDir,
            dlqDir.resolve("dlq.log"),
            record -> {},
            new RuntimeFixture.TestClock(),
            withoutVerifier,
            Optional.of(governance()));

    return new GatewayRuntimeConfig(
        base.nodeId(),
        base.configSnapshot(),
        base.masterKey(),
        base.clock(),
        base.eventing(),
        // The key snapshot must hold this test's public key and the tenant snapshot must map the
        // token's subject, or the request never reaches governance at all.
        new GatewayRuntimeConfig.AuthnConfig(keySnapshot(), RuntimeFixture.tenantSnapshot()),
        Optional.of(
            GatewayRuntimeConfig.AuthenticationConfig.snapshotOnly(
                JwtAuthenticationConfig.production(ISSUER, AUDIENCE, TENANT_CLAIM))),
        base.governance(),
        base.provider(),
        base.ingress(),
        base.plugins(),
        base.routing(),
        base.reliability(),
        base.accounting(),
        base.correctness(),
        base.secrets(),
        base.externalAdapters());
  }

  private GatewayRuntimeConfig.GovernanceConfig governance() {
    final GatewayRuntimeConfig.GovernanceConfig base =
        RuntimeFixture.governanceConfig(RuntimeFixture.permittingPolicy(), AuditSinkPort.NO_OP);
    return new GatewayRuntimeConfig.GovernanceConfig(
        base.policySnapshots(),
        base.entitlementSnapshots(),
        base.usageStates(),
        base.featureFlags(),
        base.auditSink(),
        base.usageStalenessTolerance(),
        Optional.of(
            new GatewayRuntimeConfig.PolicyEngineConfig(
                () -> Optional.ofNullable(published.get()),
                (PolicyUsagePort) scope -> Optional.of(PolicyUsage.none(NOW)),
                event -> {},
                PolicyMetrics.NO_OP,
                TickerPort.FROZEN,
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                256,
                4,
                true)),
        // The resolver that actually reads the API-key claim. Declaring it here is what makes the
        // runtime require and forward that claim.
        new GatewayRuntimeConfig.AdmissionConfig(
            new CharacterBoundTokenEstimator(0L), resolver.get()));
  }

  private RequestExecution.Inbound request(final boolean withApiKeyClaim) {
    return new RequestExecution.Inbound(
        new RequestId("req-1"),
        new io.reliabilityai.gateway.canonical.context.RequestContext(
            new CorrelationId("corr-1"),
            new IdempotencyKey("idem-1"),
            new CausationId("cause-1"),
            "traceparent",
            REGION),
        new ForwardedTransportIdentity("Bearer", "Bearer " + token(withApiKeyClaim), Map.of()),
        new CanonicalRequest(
            RuntimeFixture.MODEL, List.of(new Message("user", "hello")), List.of(), Map.of()),
        Set.of("chat"),
        1024,
        Set.of(),
        1_000_000L,
        Set.of(),
        NOW.plusSeconds(30),
        true,
        Mode.BATCH,
        Optional.empty(),
        Optional.empty(),
        Operation.CHAT,
        OptionalLong.of(16L),
        false);
  }

  private static PolicyRule denyingTheModel() {
    return PolicyRule.mandatory(
        "r-model-deny",
        PolicyType.MODEL_DENY_LIST,
        PolicyValue.Values.of(RuntimeFixture.MODEL.value()));
  }

  @Test
  void anApiKeyPolicyAuthoredAgainstThePresentedKeyRefusesTheRequest() {
    runtimeEnforcingApiKeyPolicy(PRESENTED_KEY, denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    // Reaching this assertion means the JWT authenticated, the claim was forwarded, the resolver
    // built API_KEY:K123, and the fold found the document attached to that node.
    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(refused.refusal().reason()).isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
  }

  @Test
  void theSameRequestIsAdmittedWhenTheDocumentNamesADifferentKey() {
    // Identical request and identical document; only the API-key node it hangs off differs. If this
    // is admitted, the refusal above was caused by the authenticated key identity selecting that
    // document — not by the request, the model, or the policy merely existing.
    runtimeEnforcingApiKeyPolicy(OTHER_KEY, denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome.trace().stages()).contains(MandatoryStage.GOVERNANCE);
    if (outcome instanceof PipelineOutcome.Refused refused) {
      assertThat(refused.refusal().stage()).isNotEqualTo(MandatoryStage.GOVERNANCE);
    }
  }

  /** Points the resolver and the token at a principal-type claim instead of an API-key claim. */
  private void usePrincipalTypeResolver(final String claimName, final String markerValue) {
    mintedClaim.set(claimName);
    mintedValue.set(markerValue);
    resolver.set(new ClaimBasedScopeResolver("", claimName, Optional.empty()));
  }

  /** Publishes one document at an arbitrary scope node and starts a node enforcing it. */
  private GatewayRuntime runtimeEnforcingPolicyAt(
      final PolicyScope scope, final String nodeId, final PolicyRule rule) {
    published.set(
        new PolicySourcePort.PolicyBundle(
            PolicyVersion.of("v1", 1L),
            List.of(
                GovernancePolicy.of(
                    "scoped-doc",
                    PolicyScopeRef.of(scope, nodeId),
                    PolicyVersion.of("v1", 1L),
                    List.of(rule)))));
    gateway = new GatewayRuntime(config());
    gateway.start();
    return gateway;
  }

  @Test
  void aUserPolicyAuthoredAgainstTheAuthenticatedPrincipalRefusesTheRequest() {
    // "ptype" carries anything that is not the service-account marker, so the resolver hangs a USER
    // node off the principal id taken from the token's subject.
    usePrincipalTypeResolver("ptype", "human");
    runtimeEnforcingPolicyAt(PolicyScope.USER, PRINCIPAL, denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(refused.refusal().reason()).isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
  }

  @Test
  void theSameRequestIsAdmittedWhenTheUserDocumentNamesAnotherPrincipal() {
    // The counterfactual for USER: only the node the document hangs off differs.
    usePrincipalTypeResolver("ptype", "human");
    runtimeEnforcingPolicyAt(PolicyScope.USER, "someone-else", denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome.trace().stages()).contains(MandatoryStage.GOVERNANCE);
    if (outcome instanceof PipelineOutcome.Refused refused) {
      assertThat(refused.refusal().stage()).isNotEqualTo(MandatoryStage.GOVERNANCE);
    }
  }

  @Test
  void aServiceAccountPolicyAuthoredAgainstThePrincipalRefusesTheRequest() {
    // The marker value is what decides SERVICE_ACCOUNT rather than USER.
    usePrincipalTypeResolver("ptype", ClaimBasedScopeResolver.SERVICE_ACCOUNT_MARKER);
    runtimeEnforcingPolicyAt(PolicyScope.SERVICE_ACCOUNT, PRINCIPAL, denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(refused.refusal().reason()).isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
  }

  @Test
  void aServiceAccountDocumentDoesNotGovernAUserPrincipal() {
    // Proves the marker actually selects the scope: the same principal, classified as human, must
    // not pick up a document authored at SERVICE_ACCOUNT.
    usePrincipalTypeResolver("ptype", "human");
    runtimeEnforcingPolicyAt(PolicyScope.SERVICE_ACCOUNT, PRINCIPAL, denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome.trace().stages()).contains(MandatoryStage.GOVERNANCE);
    if (outcome instanceof PipelineOutcome.Refused refused) {
      assertThat(refused.refusal().stage()).isNotEqualTo(MandatoryStage.GOVERNANCE);
    }
  }

  /** Publishes two documents at once, so parent and child semantics can be compared. */
  private GatewayRuntime runtimeEnforcing(final GovernancePolicy... documents) {
    published.set(
        new PolicySourcePort.PolicyBundle(PolicyVersion.of("v1", 1L), List.of(documents)));
    gateway = new GatewayRuntime(config());
    gateway.start();
    return gateway;
  }

  private static GovernancePolicy documentAt(
      final String id, final PolicyScope scope, final String nodeId, final PolicyRule rule) {
    return GovernancePolicy.of(
        id, PolicyScopeRef.of(scope, nodeId), PolicyVersion.of("v1", 1L), List.of(rule));
  }

  @Test
  void anUnmatchedChildDocumentDoesNotEraseTheOrganizationPolicy() {
    // The semantics of absence. The request presents K123; the only API_KEY document is authored at
    // K999, so no child node contributes. An organization document still governs the request, which
    // proves an absent child means "inherit the parent" and never "allow anything".
    runtimeEnforcing(
        documentAt("org-doc", PolicyScope.ORGANIZATION, ORG, denyingTheModel()),
        documentAt("key-doc", PolicyScope.API_KEY, OTHER_KEY, denyingTheModel()));

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(refused.refusal().reason()).isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
  }

  @Test
  void aMatchedChildDocumentTightensWhereTheOrganizationPermits() {
    // The mirror: no organization document at all, and the child authored at the presented key.
    // Refusal here can only come from the child node, so the child is doing the tightening.
    runtimeEnforcing(documentAt("key-doc", PolicyScope.API_KEY, PRESENTED_KEY, denyingTheModel()));

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    assertThat(((PipelineOutcome.Refused) outcome).refusal().stage())
        .isEqualTo(MandatoryStage.GOVERNANCE);
  }

  @Test
  void aRequestScopedDocumentIsAcceptedByPublicationButNeverEvaluated() {
    // Characterises PolicyScope.REQUEST. The document is authored, compiles, and installs — if it
    // had not compiled the node would come up with no generation and refuse everything, so the
    // admission below is itself the proof that publication succeeded. Yet the shipped resolver
    // never builds a REQUEST node, so PolicySnapshot.effectiveFor never folds the document and the
    // restriction it carries has no effect. Compare with the ORGANIZATION case above, where the
    // identical rule refuses: the rule works; the scope is what the runtime never reaches.
    runtimeEnforcing(documentAt("req-doc", PolicyScope.REQUEST, "req-1", denyingTheModel()));

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome.trace().stages()).contains(MandatoryStage.GOVERNANCE);
    if (outcome instanceof PipelineOutcome.Refused refused) {
      assertThat(refused.refusal().stage())
          .as("a REQUEST-scoped document must not reach governance evaluation")
          .isNotEqualTo(MandatoryStage.GOVERNANCE);
    }
  }

  @Test
  void aCustomResolverDeclaringItsOwnClaimDrivesTheSameChain() {
    // The declaration used to be recovered by narrowing to the shipped resolver, so a resolver an
    // operator wrote was handed an empty set: its claim never arrived and its node never appeared.
    // Nothing here is a ClaimBasedScopeResolver, so a refusal can only mean GatewayRuntime asked
    // the *contract* what it needed and the verifier forwarded exactly that.
    final String customClaim = "custom_api_key";
    mintedClaim.set(customClaim);
    resolver.set(
        new GovernanceScopeResolver() {
          @Override
          public Set<String> requiredClaims() {
            return Set.of(customClaim);
          }

          @Override
          public ScopeChain resolve(
              final io.reliabilityai.gateway.canonical.context.PrincipalContext principal,
              final io.reliabilityai.gateway.canonical.context.TenantContext tenant) {
            return ScopeChain.builder()
                .organization(tenant.tenantScope().org())
                .apiKey(principal.claims().get(customClaim))
                .build();
          }
        });

    runtimeEnforcingApiKeyPolicy(PRESENTED_KEY, denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(true));

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(refused.refusal().reason()).isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
  }

  @Test
  void aCustomResolverStillFailsClosedWhenItsDeclaredClaimIsAbsent() {
    final String customClaim = "custom_api_key";
    mintedClaim.set(customClaim);
    resolver.set(
        new GovernanceScopeResolver() {
          @Override
          public Set<String> requiredClaims() {
            return Set.of(customClaim);
          }

          @Override
          public ScopeChain resolve(
              final io.reliabilityai.gateway.canonical.context.PrincipalContext principal,
              final io.reliabilityai.gateway.canonical.context.TenantContext tenant) {
            return ScopeChain.forTenant(tenant.tenantScope());
          }
        });

    runtimeEnforcingApiKeyPolicy(PRESENTED_KEY, denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(false));

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    assertThat(((PipelineOutcome.Refused) outcome).refusal().stage())
        .isEqualTo(MandatoryStage.AUTHN);
  }

  @Test
  void aTokenOmittingTheDeclaredApiKeyClaimIsRefusedBeforeGovernance() {
    // Fail-closed, end to end: the deployment declared the claim security-relevant, so a token
    // without it is refused at AUTHN rather than admitted with the API_KEY node quietly missing.
    runtimeEnforcingApiKeyPolicy(PRESENTED_KEY, denyingTheModel());

    final PipelineOutcome outcome = gateway.pipeline().execute(request(false));

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.AUTHN);
    assertThat(refused.refusal().reason()).isEqualTo("missing-required-claim");
    assertThat(outcome.trace().stages()).doesNotContain(MandatoryStage.GOVERNANCE);
  }
}
