package io.reliabilityai.gateway.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-lib-common} — Doc 35 §CmC (Common Package Contract).
 * {@code common} holds only stateless primitives; no domain/business/framework dependencies.
 */
class CommonPackageTest {

  private static final com.tngtech.archunit.core.domain.JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.reliabilityai.gateway.common");

  @Test
  void commonHasNoFrameworkOrDomainDependencies() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.reliabilityai.gateway.common..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "org.apache.kafka..",
                "org.apache.avro..",
                "jakarta.persistence..",
                "io.reliabilityai.gateway.canonical..",
                "io.reliabilityai.gateway.ports..");
    rule.check(CLASSES);
  }

  @Test
  void commonTypesAreFinalOrInterfaces() {
    final ArchRule rule =
        classes()
            .that()
            .resideInAPackage("io.reliabilityai.gateway.common..")
            .and()
            .areNotInterfaces()
            .and()
            .areNotEnums()
            .should()
            .haveModifier(com.tngtech.archunit.core.domain.JavaModifier.FINAL);
    rule.check(CLASSES);
  }
}
