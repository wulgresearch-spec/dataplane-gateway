package io.reliabilityai.gateway.dataplane.schemalock;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-schemalock} (Doc 17 §6/§51, SL-A1..A14). No provider
 * SDK (SL-A1), no concrete infra — DB driver, web framework, or <b>JSON Schema validator/JSON
 * library</b> (SL-A2, the validator is behind {@code SchemaValidatorPort}); no dependency on the
 * router/reliability/streamguard/secrets modules; no wall-clock/random in the validation core
 * (SL-A4, R-063); inward dependency direction (R-017); context-first packaging (R-013).
 */
class SchemaLockArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.schemalock";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void noSdkValidatorLibraryPersistenceOrForeignModuleDependency() {
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
                "com.networknt..",
                "com.fasterxml.jackson..",
                "com.github.fge..",
                "java.net.http..",
                "java.sql..",
                "jakarta..",
                "io.reliabilityai.gateway.dataplane.router..",
                "io.reliabilityai.gateway.dataplane.reliability..",
                "io.reliabilityai.gateway.dataplane.streamguard..",
                "io.reliabilityai.gateway.dataplane.secrets..");
    rule.check(CLASSES);
  }

  @Test
  void validationCoreUsesNoWallClockOrRandom() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + "..")
            .should()
            .callMethod(java.time.Instant.class, "now")
            .orShould()
            .dependOnClassesThat()
            .resideInAnyPackage("java.util.Random..", "java.util.concurrent.ThreadLocalRandom..");
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
