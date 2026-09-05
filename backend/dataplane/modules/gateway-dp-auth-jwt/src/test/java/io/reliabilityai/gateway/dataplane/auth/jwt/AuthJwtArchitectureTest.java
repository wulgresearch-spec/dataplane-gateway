package io.reliabilityai.gateway.dataplane.auth.jwt;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-auth-jwt} — the authentication/authorization fence
 * (Doc 37 §ANZ, IAU-A17), applied to the module that actually performs verification.
 *
 * <p>The sibling rule on {@code gateway-dp-authn} has always covered the domain module, but the
 * verifier lives here, and this module had the ArchUnit dependency declared with no rule using it.
 * That gap matters now: the verifier was recently taught to forward operator-declared governance
 * claims, and the safe way to do that was to accept plain claim <em>names</em> rather than any
 * governance type. Nothing in the compiler stops a later change from importing the governance
 * engine directly and quietly turning the authentication node into a second policy decision point.
 * This rule is what keeps that a build failure rather than a review question.
 *
 * <p>HTTP is deliberately <b>not</b> forbidden here, unlike in {@code gateway-dp-authn}: {@code
 * JwksHttpSource} fetches a JWKS document out of band. The prohibition that matters is on the
 * request path, and lookups never fetch.
 */
class AuthJwtArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.auth.jwt";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void theVerifierNeverDependsOnGovernancePolicyOrTheApplicationLayer() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                // §ANZ / IAU-A17: authentication never evaluates policy and never becomes a PDP.
                // Forwarding governance claims must stay a set of names, never a governance type.
                "..governance..",
                "..authorization..",
                "..policy..",
                // The scope resolver and runtime wiring live in the application layer; depending on
                // them here would invert the module direction and re-couple the two configurations
                // that were deliberately reduced to one declaration.
                "io.reliabilityai.gateway.dataplane.app..",
                // IAU-A9: verification keys are public; provider credentials are Doc 26's.
                "io.reliabilityai.gateway.dataplane.secrets..",
                "io.reliabilityai.gateway.canonical.secret..",
                // No framework, no persistence, no broker.
                "org.springframework..",
                "org.apache.kafka..",
                "java.sql..",
                "jakarta..");

    rule.check(CLASSES);
  }
}
