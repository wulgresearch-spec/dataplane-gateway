package io.reliabilityai.gateway.dataplane.governance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit conformance for {@code gateway-dp-governance-engine} (Doc 21 §45, GV-A9..A13).
 *
 * <p>These rules exist because the corresponding properties are easy to state and easy to erode. An
 * admission gate that acquires a database connection, an HTTP client or a provider SDK stops being
 * an admission gate and becomes a dependency — and the day that dependency is slow is the day
 * nothing gets admitted. Likewise a gate that reads the wall clock directly stops being replayable,
 * which quietly removes the ability to answer "why was this refused" after the fact.
 *
 * <p>What is deliberately <em>not</em> asserted here is slice acyclicity. The module's ports carry
 * its domain value objects by design — {@code PolicySnapshotPort} returns a {@code PolicySet} — so
 * api and domain reference each other, and a cycle rule would fail on frozen code that is correct
 * as it stands. The layering rules below assert the direction that genuinely matters instead:
 * nothing outside the application layer may reach into internals or into orchestration.
 */
class GovernanceArchitectureTest {

  private static final String ROOT = "io.reliabilityai.gateway.dataplane.governance";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages(ROOT);

  @Test
  void noSdkHttpPersistenceOrFrameworkDependency() {
    // GV-A10/A11: the engine owns no store, opens no socket and touches no credential. Every input
    // is
    // an already-cached snapshot handed to it; there is nothing here for an outage to make slow.
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "org.apache.kafka..",
                "java.net..",
                "java.net.http..",
                "java.sql..",
                "javax.sql..",
                "java.io..",
                "java.nio.file..",
                "jakarta..");
    rule.check(CLASSES);
  }

  @Test
  void noDependencyOnAnyOtherDataPlaneModule() {
    // Governance decides admission from policy alone. A dependency on the router or the provider
    // adapter would be a route back into exactly the provider coupling GV-D11 forbids.
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage(ROOT + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "io.reliabilityai.gateway.dataplane.router..",
                "io.reliabilityai.gateway.dataplane.reliability..",
                "io.reliabilityai.gateway.dataplane.provider..",
                "io.reliabilityai.gateway.dataplane.schemalock..",
                "io.reliabilityai.gateway.dataplane.streamguard..",
                "io.reliabilityai.gateway.dataplane.metering..",
                "io.reliabilityai.gateway.dataplane.cost..",
                "io.reliabilityai.gateway.dataplane.secrets..",
                "io.reliabilityai.gateway.dataplane.app..");
    rule.check(CLASSES);
  }

  @Test
  void theDecisionCoreReadsNoWallClockAndNoRandomness() {
    // GV-A13/GV-D12: time arrives through ClockPort and elapsed time through TickerPort, so a
    // decision
    // is reproducible from its recorded inputs rather than from whatever the machine's clock said.
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
  void internalsStayInternal() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAnyPackage(ROOT + ".api..", ROOT + ".domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ROOT + ".internal..");
    rule.check(CLASSES);
  }

  @Test
  void orchestrationIsNotReachableFromThePolicyModel() {
    final ArchRule rule =
        noClasses()
            .that()
            .resideInAnyPackage(ROOT + ".api..", ROOT + ".domain..", ROOT + ".internal..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage(ROOT + ".application..");
    rule.check(CLASSES);
  }

  @Test
  void moduleIsPackagedApiDomainInternalApplication() {
    classes()
        .that()
        .resideInAPackage(ROOT + "..")
        .should()
        .resideInAnyPackage(
            ROOT + ".api..", ROOT + ".application..", ROOT + ".domain..", ROOT + ".internal..")
        .check(CLASSES);
  }
}
