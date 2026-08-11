package io.reliabilityai.gateway.dataplane.metering;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-metering} (Doc 23 §7/§51, UME-A5..A13). No provider
 * SDK, HTTP, persistence, or secrets (UME-D12/A11/A13); no pricing/billing (UME-A5/A6 — Cost
 * Engine's/C8's); no dependency on the router/reliability/schemalock/streamguard/secrets modules;
 * no wall-clock/random in the metering core (UME-A8, R-063 — time via ClockPort); inward dependency
 * direction (R-017); context-first packaging (R-013).
 */
class MeteringArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.metering";

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
                "java.net.http..",
                "java.sql..",
                "jakarta..",
                "io.reliabilityai.gateway.dataplane.router..",
                "io.reliabilityai.gateway.dataplane.reliability..",
                "io.reliabilityai.gateway.dataplane.schemalock..",
                "io.reliabilityai.gateway.dataplane.streamguard..",
                "io.reliabilityai.gateway.dataplane.secrets..");
    rule.check(CLASSES);
  }

  @Test
  void meteringCoreUsesNoWallClockOrRandom() {
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
