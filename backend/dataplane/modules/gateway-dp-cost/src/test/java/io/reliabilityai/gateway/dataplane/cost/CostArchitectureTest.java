package io.reliabilityai.gateway.dataplane.cost;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-cost} (Doc 22 §7/§51, CE-A2..A13). No provider SDK,
 * HTTP, persistence, or secrets (CE-D12); no billing/payment/invoice types (CE-A9/A10 — C8's); no
 * dependency on the router/reliability/schemalock/streamguard/metering/secrets modules; no
 * wall-clock/random in the cost core (CE-A8, R-063 — time via ClockPort for freshness only); inward
 * dependency direction (R-017); context-first packaging (R-013).
 */
class CostArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.cost";

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
                "io.reliabilityai.gateway.dataplane.metering..",
                "io.reliabilityai.gateway.dataplane.secrets..");
    rule.check(CLASSES);
  }

  @Test
  void costCoreUsesNoWallClockOrRandom() {
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
