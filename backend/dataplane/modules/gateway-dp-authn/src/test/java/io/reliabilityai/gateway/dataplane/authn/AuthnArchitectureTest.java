package io.reliabilityai.gateway.dataplane.authn;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-authn} (Doc 37 §5 Enforcement, IAU-A1/A9/A17).
 * Enforces: <b>no authorization/governance/policy dependency</b> (authentication only, §ANZ), <b>no
 * secret/ credential dependency</b> (verification keys are public, IAU-A9), no online-IdP/HTTP
 * client on the hot path (IAU-A4), no framework/persistence, inward dependency direction (R-017),
 * and context-first packaging (R-013, Doc 35 §ILA).
 */
class AuthnArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.authn";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void noAuthorizationSecretFrameworkOrIdpDependency() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                // §ANZ / IAU-A1/A17: never authorization/governance/policy
                "..governance..",
                "..authorization..",
                "..policy..",
                // IAU-A9: never a provider secret/credential (that is Doc 26)
                "io.reliabilityai.gateway.dataplane.secrets..",
                "io.reliabilityai.gateway.canonical.secret..",
                // IAU-A4: no online IdP / HTTP client on the hot path; no framework/persistence
                "org.springframework..",
                "java.net.http..",
                "org.apache.kafka..",
                "java.sql..",
                "jakarta..");
    rule.check(CLASSES);
  }

  @Test
  void domainDependsOnlyInward() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + ".domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(ROOT + ".application..", ROOT + ".api..");
    rule.check(CLASSES);
  }

  @Test
  void moduleIsContextFirstAndFreeOfCycles() {
    classes()
        .that()
        .resideInAPackage(ROOT + "..")
        .should()
        .resideInAnyPackage(ROOT + ".api..", ROOT + ".application..", ROOT + ".domain..")
        .check(CLASSES);
    slices()
        .matching(ROOT + ".(*)..")
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true)
        .check(CLASSES);
  }
}
