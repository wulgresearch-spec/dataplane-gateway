package io.reliabilityai.gateway.dataplane.router;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-router} (Doc 19 §7 Enforcement, PR-D12). No provider
 * SDK, HTTP client, persistence, secrets, or framework; no dependency on
 * correctness/streaming/retry/secrets modules; inward dependency direction (R-017); context-first
 * packaging (R-013, Doc 35 §ILA).
 */
class RouterArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.router";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void noSdkHttpPersistenceSecretsOrForeignModuleDependency() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "org.apache.kafka..",
                "org.apache.avro..",
                "java.net.http..",
                "java.sql..",
                "jakarta..",
                "io.reliabilityai.gateway.canonical.secret..",
                "io.reliabilityai.gateway.dataplane.secrets..");
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
