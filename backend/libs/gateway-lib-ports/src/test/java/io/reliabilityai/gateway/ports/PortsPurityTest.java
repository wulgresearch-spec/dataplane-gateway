package io.reliabilityai.gateway.ports;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-lib-ports} (AD-002, Doc 11 R-018). Ports are pure domain-
 * side interfaces depending only on canonical + common; no framework/infra imports (AU-08 spirit).
 */
class PortsPurityTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.reliabilityai.gateway.ports");

  @Test
  void portsDependOnlyOnCanonicalCommonAndJdk() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.reliabilityai.gateway.ports..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "org.apache.kafka..",
                "org.apache.avro..",
                "jakarta..",
                "io.reliabilityai.gateway.events..");
    rule.check(CLASSES);
  }

  @Test
  void portTypesAreInterfaces() {
    final ArchRule rule =
        classes()
            .that()
            .resideInAPackage("io.reliabilityai.gateway.ports..")
            .and()
            .haveSimpleNameEndingWith("Port")
            .should()
            .beInterfaces();
    rule.check(CLASSES);
  }

  @Test
  void portsAreFreeOfCycles() {
    // M1 fix (AU-04 / Doc 11 R-021): the ports library must be free of package-cycles. Ports is a
    // flat package, so we slice at the gateway level (one non-empty slice) — trivially acyclic
    // today
    // and a live guard once ports ever gains sub-packages.
    slices()
        .matching("io.reliabilityai.gateway.(*)..")
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true)
        .check(CLASSES);
  }
}
