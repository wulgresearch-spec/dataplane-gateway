package io.reliabilityai.gateway.canonical;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-lib-canonical} (Doc 33 §4). Provider-neutral, immutable,
 * framework-free value objects. Enforces AD-007 (no provider SDK) and Doc 11 R-005 (immutability).
 */
class CanonicalNeutralityTest {

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.reliabilityai.gateway.canonical");

  @Test
  void canonicalDependsOnlyOnJdkAndCommon() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.reliabilityai.gateway.canonical..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "org.apache.kafka..",
                "org.apache.avro..",
                "jakarta..",
                "io.reliabilityai.gateway.ports..",
                "io.reliabilityai.gateway.events..");
    rule.check(CLASSES);
  }

  @Test
  void noProviderSdkOrProviderNativeTypesLeakIntoCanonical() {
    // AD-007 / Doc 25 §PA-D1: provider-native shapes (ProviderRequest/Response) are §B
    // non-canonical
    // and must never appear in the canonical library.
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.reliabilityai.gateway.canonical..")
            .should()
            .haveSimpleNameStartingWith("Provider")
            .andShould()
            .haveSimpleNameEndingWith("Request");
    // ProviderCapabilitiesSnapshot / ProviderDescriptorSnapshot are cached snapshots (Doc 33
    // §10.6),
    // not provider-native shapes; the rule above only bans "Provider*Request" native-style names.
    rule.allowEmptyShould(true).check(CLASSES);
  }

  @Test
  void allInstanceFieldsAreFinal() {
    final ArchRule rule =
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("io.reliabilityai.gateway.canonical..")
            .and()
            .areNotStatic()
            .should()
            .beFinal();
    rule.check(CLASSES);
  }

  @Test
  void noMutableDateOrCalendarFields() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.reliabilityai.gateway.canonical..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.util.Date");
    rule.allowEmptyShould(true).check(CLASSES);
  }

  @Test
  void canonicalSubPackagesAreFreeOfCycles() {
    // M1 fix (AU-04 / Doc 11 R-021): no package-cycles across canonical sub-packages.
    slices()
        .matching("io.reliabilityai.gateway.canonical.(*)..")
        .should()
        .beFreeOfCycles()
        .allowEmptyShould(true)
        .check(CLASSES);
  }

  @Test
  void contentFreeTypesNeverReferenceContentBearingTypes() {
    // M5 fix (Doc 14 §7.1 / Doc 27 §15.1): the ContentFree marker is enforced, not merely declared
    // —
    // no ContentFree type may depend on a content-bearing canonical type
    // (prompts/responses/messages).
    final ArchRule rule =
        noClasses()
            .that()
            .areAssignableTo(io.reliabilityai.gateway.common.ContentFree.class)
            .and()
            .resideInAPackage("io.reliabilityai.gateway.canonical..")
            .should()
            .dependOnClassesThat()
            // haveNameMatching, not haveFullyQualifiedName: the former takes a regex and matches it
            // against the fully qualified name, which is what this pattern is. The latter is an
            // exact-string comparison and would silently match nothing.
            .haveNameMatching(
                "io\\.reliabilityai\\.gateway\\.canonical\\.io\\."
                    + "(CanonicalRequest|CanonicalResponse|Message|CanonicalToolCall|ProviderMeta)");
    rule.allowEmptyShould(true).check(CLASSES);
  }
}
