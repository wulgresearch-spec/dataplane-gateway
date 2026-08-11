package io.reliabilityai.gateway.dataplane.observability;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-observability} (Doc 27 §4 Enforcement, OT-A1/OT-A2).
 * Enforces: no framework/SDK/persistence dependency, no leakage of routing/retry/governance/pricing
 * or other DP-module types (side-effect-free, cannot alter a decision), inward dependency direction
 * (R-017), and context-first packaging (R-013, Doc 35 §ILA).
 */
class ObservabilityArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.observability";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void noFrameworkSdkOrForeignModuleDependency() {
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
                // side-effect-free: must not touch other DP modules or their decision/mutation
                // types
                "io.reliabilityai.gateway.dataplane.config..",
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
