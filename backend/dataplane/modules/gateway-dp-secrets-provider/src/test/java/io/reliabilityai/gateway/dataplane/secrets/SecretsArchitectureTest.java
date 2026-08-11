package io.reliabilityai.gateway.dataplane.secrets;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-secrets-provider} (Doc 26 §7 Enforcement,
 * SP-A1/A2/A8). Enforces: no provider SDK/HTTP-to-provider (AD-007, SP-A8), no persistence/store
 * (SP-A1), no framework/synchronous control-plane dependency (SP-A2), inward dependency direction
 * (R-017), and context-first packaging (R-013, Doc 35 §ILA).
 */
class SecretsArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.secrets";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void noPersistenceProviderSdkOrFrameworkDependency() {
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
                "javax.sql..",
                "jakarta.persistence..",
                "io.reliabilityai.gateway.events..");
    rule.check(CLASSES);
  }

  @Test
  void leaseIsNotSerializable() {
    // Doc 26 §17.1/§20.1: nothing holding credential material may be serialized, because a
    // serialized credential is a credential written somewhere nobody audited.
    //
    // Throwables and enums are excluded, and the exclusion is not a softening. Both are assignable
    // to Serializable through the JDK's own base classes — `Throwable implements Serializable` and
    // `Enum implements Serializable` — so no code in this package could satisfy the rule while
    // still declaring an exception or an enum. As originally written the rule was unsatisfiable
    // rather than strict, which is why KmsException and KmsException.Reason violated it the very
    // first time the ArchUnit suite was able to run. Neither carries credential material.
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + "..")
            .and()
            .areNotAssignableTo(Throwable.class)
            .and()
            .areNotEnums()
            .should()
            .beAssignableTo(java.io.Serializable.class);
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
            .resideInAnyPackage(ROOT + ".application..", ROOT + ".adapter..");
    rule.check(CLASSES);
  }

  @Test
  void applicationDoesNotDependOnAdapter() {
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
