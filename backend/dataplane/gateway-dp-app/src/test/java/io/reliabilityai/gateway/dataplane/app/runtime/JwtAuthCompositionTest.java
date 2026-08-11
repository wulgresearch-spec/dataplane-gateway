package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKey;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.StartupValidationException;
import io.reliabilityai.gateway.dataplane.auth.jwt.JwtAuthenticationConfig;
import io.reliabilityai.gateway.dataplane.auth.jwt.JwtIdentityVerifier;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The runtime must construct the real JWT verifier and use it for the AUTHN stage. */
class JwtAuthCompositionTest {

  private static final String ISSUER = "https://issuer.example";
  private static final String AUDIENCE = "gateway";
  private static final String KID = "kid-1";

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private final KeyPair keyPair = rsaKeyPair();
  private GatewayRuntime runtime;

  @AfterEach
  void tearDown() {
    if (runtime != null && runtime.state() == GatewayRuntime.State.READY) {
      runtime.stop();
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

  /** A key snapshot carrying the generated public key, replacing the fixture's empty one. */
  private VerificationKeySnapshot keySnapshot() {
    return new VerificationKeySnapshot(
        new SnapshotVersion("verification-keys", "v1"),
        new Region("us-east-1"),
        List.of(
            new VerificationKey(
                KID,
                JwsAlgorithm.RS256,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()))),
        List.of());
  }

  private GatewayRuntime start(
      final Optional<GatewayRuntimeConfig.AuthenticationConfig> authentication) {
    runtime =
        new GatewayRuntime(
            RuntimeFixture.config(
                RuntimeFixture.masterKey(),
                walDir,
                dlqDir.resolve("dlq.log"),
                record -> {},
                new RuntimeFixture.TestClock(),
                // No external verifier: AUTHN must bind from the runtime's own JWT verifier.
                withoutIdentityVerifier(),
                Optional.of(
                    RuntimeFixture.governanceConfig(
                        RuntimeFixture.permittingPolicy(), AuditSinkPort.NO_OP)),
                Optional.of(
                    RuntimeFixture.providerConfig(java.net.URI.create("http://127.0.0.1:1"))),
                Optional.empty(),
                authentication));
    runtime.start();
    return runtime;
  }

  private static ExternalAdapters withoutIdentityVerifier() {
    final ExternalAdapters all = RuntimeFixture.allExternalAdapters();
    return new ExternalAdapters(
        all.ingress(),
        Optional.empty(),
        all.governance(),
        all.providerTransport(),
        all.providerTranslator(),
        all.credentialPort(),
        all.capabilitySnapshot(),
        all.schemaValidator(),
        all.providerGeneration());
  }

  private static GatewayRuntimeConfig.AuthenticationConfig authConfig() {
    return GatewayRuntimeConfig.AuthenticationConfig.snapshotOnly(
        JwtAuthenticationConfig.production(ISSUER, AUDIENCE, "tid"));
  }

  private String token(final long expiresAt) {
    final String header = base64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KID + "\"}");
    final String payload =
        base64(
            "{\"iss\":\""
                + ISSUER
                + "\",\"aud\":\""
                + AUDIENCE
                + "\",\"sub\":\"principal-a\",\"tid\":\"tenant-a\",\"exp\":"
                + expiresAt
                + "}");
    final String signingInput = header + '.' + payload;
    try {
      final Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(keyPair.getPrivate());
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput
          + '.'
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

  @Test
  void runtimeConstructsTheJwtVerifierAndBindsAuthn() {
    start(Optional.of(authConfig()));

    assertThat(runtime.authentication()).isPresent();
    assertThat(runtime.boundStages()).contains(MandatoryStage.AUTHN);
    assertThat(runtime.jwksCache()).isEmpty(); // snapshot-only configuration
  }

  @Test
  void startupFailsClosedWhenNeitherAJwtConfigNorAnExternalVerifierIsSupplied() {
    runtime =
        new GatewayRuntime(
            RuntimeFixture.config(
                RuntimeFixture.masterKey(),
                walDir,
                dlqDir.resolve("dlq.log"),
                record -> {},
                new RuntimeFixture.TestClock(),
                withoutIdentityVerifier(),
                Optional.of(
                    RuntimeFixture.governanceConfig(
                        RuntimeFixture.permittingPolicy(), AuditSinkPort.NO_OP)),
                Optional.of(
                    RuntimeFixture.providerConfig(java.net.URI.create("http://127.0.0.1:1"))),
                Optional.empty(),
                Optional.empty()));

    assertThatThrownBy(runtime::start)
        .isInstanceOf(StartupValidationException.class)
        .hasMessageContaining("AUTHN");
  }

  @Test
  void theConstructedVerifierAcceptsAValidTokenAgainstTheConfiguredSnapshot() {
    // The runtime's own verifier, exercised against the same snapshot the runtime would hand it.
    final JwtIdentityVerifier verifier =
        new JwtIdentityVerifier(
            JwtAuthenticationConfig.production(ISSUER, AUDIENCE, "tid"),
            () -> Instant.parse("2026-01-01T00:00:00Z"));

    final var outcome =
        verifier.verify(
            new io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity(
                "Bearer",
                "Bearer " + token(Instant.parse("2026-01-01T01:00:00Z").getEpochSecond()),
                java.util.Map.of()),
            keySnapshot());

    assertThat(outcome)
        .isInstanceOf(
            io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome.Verified.class);
  }

  @Test
  void theConstructedVerifierRejectsAnExpiredToken() {
    final JwtIdentityVerifier verifier =
        new JwtIdentityVerifier(
            JwtAuthenticationConfig.production(ISSUER, AUDIENCE, "tid"),
            () -> Instant.parse("2026-01-01T00:00:00Z"));

    final var outcome =
        verifier.verify(
            new io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity(
                "Bearer",
                "Bearer " + token(Instant.parse("2025-01-01T00:00:00Z").getEpochSecond()),
                java.util.Map.of()),
            keySnapshot());

    assertThat(outcome)
        .isInstanceOf(
            io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome.Rejected.class);
  }

  @Test
  void aFailedJwksPrimeNeverPreventsStartup() {
    // Nothing is listening on port 1, so the initial JWKS fetch cannot succeed.
    final GatewayRuntimeConfig.AuthenticationConfig withJwks =
        new GatewayRuntimeConfig.AuthenticationConfig(
            JwtAuthenticationConfig.production(ISSUER, AUDIENCE, "tid"),
            Optional.of(java.net.URI.create("http://127.0.0.1:1/jwks.json")),
            java.time.Duration.ofMinutes(15),
            java.time.Duration.ofMillis(300),
            java.time.Duration.ofMillis(500));

    start(Optional.of(withJwks));

    // An identity provider being briefly unreachable must not keep the gateway down.
    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.READY);
    assertThat(runtime.jwksCache()).isPresent();
    assertThat(runtime.jwksCache().orElseThrow().isStale()).isTrue();
  }
}
