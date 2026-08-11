package io.reliabilityai.gateway.dataplane.config;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-config-cache} (Doc 36, Doc 11 R-013/R-017, Doc 35
 * §ILA). Enforces provider/framework neutrality, no synchronous control-plane dependency on the hot
 * path (Doc 36 CFG-A2), inward dependency direction, and context-first packaging.
 */
class ConfigCacheArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.config";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void noSynchronousControlPlaneOrFrameworkDependency() {
    // CFG-A2: config is consumed via cached snapshots only — no CP client, HTTP, Kafka, or Spring
    // on the hot path (AD-022). The consumer authors nothing framework-bound.
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
                "jakarta..",
                "io.reliabilityai.gateway.events..");
    rule.check(CLASSES);
  }

  @Test
  void domainDependsOnlyInward() {
    // R-017: domain depends on nothing outward (no application/adapter/api of this module).
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + ".domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(ROOT + ".application..", ROOT + ".adapter..", ROOT + ".api..");
    rule.check(CLASSES);
  }

  @Test
  void applicationDoesNotDependOnAdapter() {
    // R-017: use-cases depend on domain + ports, never on outbound adapters.
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + ".application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ROOT + ".adapter..");
    rule.check(CLASSES);
  }

  @Test
  void moduleIsContextFirstAndFreeOfCycles() {
    // R-013 / Doc 35 §ILA: context-first layout; AU-04: no package cycles.
    classes()
        .that()
        .resideInAPackage(ROOT + "..")
        .should()
        .resideInAnyPackage(
            ROOT + ".api..", ROOT + ".application..", ROOT + ".domain..", ROOT + ".adapter..")
        .check(CLASSES);
    slices()
        .matching(ROOT + ".(*)..")
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true)
        .check(CLASSES);
  }
}
