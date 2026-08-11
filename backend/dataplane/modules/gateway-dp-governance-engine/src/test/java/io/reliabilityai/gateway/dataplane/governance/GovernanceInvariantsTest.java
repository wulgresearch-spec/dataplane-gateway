package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.CLOCK;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.GLOBAL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.document;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.request;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.rule;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.snapshot;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.usage;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAuditEvent;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyViolation;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngine;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyEvaluator;
import io.reliabilityai.gateway.dataplane.governance.internal.InProcessPolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyRegistry;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyStore;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the module-level invariants that no single unit test would catch: provider neutrality, the
 * absence of persistence and I/O, and the content-free guarantee on everything that leaves the
 * module.
 *
 * <p>These are the checks a build-time architecture rule would normally make. Doing them
 * reflectively over the compiled classes rather than by reading source means they hold for whatever
 * was actually built, which is the thing that ships.
 */
class GovernanceInvariantsTest {

  private static final String PACKAGE = "io.reliabilityai.gateway.dataplane.governance";

  /**
   * Names no governance type may mention. Governance is about tenants and policy, never providers.
   */
  private static final List<String> PROVIDER_NAMES =
      List.of("openai", "anthropic", "bedrock", "vertex", "azure", "cohere", "mistral", "gemini");

  /** Package prefixes that would mean this module had acquired a store, a socket or a file. */
  private static final List<String> FORBIDDEN_PACKAGES =
      List.of("java.io.", "java.nio.file.", "java.sql.", "java.net.", "javax.sql.");

  /**
   * Types a content-free record component may be built from: ids, codes, counts, timestamps, and
   * collections of them. Notably absent are any type that could hold a prompt, a completion, a
   * claim set or a credential.
   */
  private static final Set<Class<?>> CONTENT_FREE_TYPES =
      Set.of(
          String.class,
          Instant.class,
          boolean.class,
          long.class,
          int.class,
          List.class,
          java.util.Map.class);

  // ---- provider neutrality --------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(PolicyType.class)
  void noPolicyTypeIsNamedAfterAProvider(final PolicyType type) {
    final String name = type.name().toLowerCase(Locale.ROOT);

    assertThat(PROVIDER_NAMES).noneSatisfy(provider -> assertThat(name).contains(provider));
  }

  @Test
  void noGovernanceClassIsNamedAfterAProvider() throws Exception {
    for (final Class<?> type : governanceClasses()) {
      final String name = type.getName().toLowerCase(Locale.ROOT);
      assertThat(PROVIDER_NAMES)
          .as("class %s", type.getName())
          .noneSatisfy(provider -> assertThat(name).contains(provider));
    }
  }

  @Test
  void noGovernanceClassDeclaresAConstantNamingAProvider() throws Exception {
    for (final Class<?> type : governanceClasses()) {
      for (final Field field : type.getDeclaredFields()) {
        final String name = field.getName().toLowerCase(Locale.ROOT);
        assertThat(PROVIDER_NAMES)
            .as("%s.%s", type.getSimpleName(), field.getName())
            .noneSatisfy(provider -> assertThat(name).contains(provider));
      }
    }
  }

  @Test
  void providerIdentityIsOpaqueSoRenamingEveryProviderChangesNothing() {
    // Governance compares provider ids by set membership and never by what they say. Relabelling
    // every provider must therefore produce structurally identical decisions.
    final var registry = new PolicyRegistry(new PolicyStore(64, 2), new InProcessPolicyMetrics());
    registry.install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "p",
                    io.reliabilityai.gateway.dataplane.governance.api.PolicyType.PROVIDER_DENY_LIST,
                    PolicyValue.Values.of("alpha")))));
    final var engine = engineOver(registry);

    final PolicyDecision denied =
        engine.govern(request().candidateProviders(Set.of("alpha")).build());
    final PolicyDecision admitted =
        engine.govern(request().candidateProviders(Set.of("beta")).build());

    assertThat(denied.verdict()).isEqualTo(Verdict.DENY);
    assertThat(admitted.verdict()).isEqualTo(Verdict.ALLOW);
  }

  @Test
  void theSameRequestIsGovernedIdenticallyWhicheverProviderCouldServeIt() {
    final var registry = new PolicyRegistry(new PolicyStore(64, 2), new InProcessPolicyMetrics());
    registry.install(
        snapshot(
            1L,
            document(GLOBAL, 1L, rule("c", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(100)))));
    final var engine = engineOver(registry);

    final PolicyDecision first =
        engine.govern(request().candidateProviders(Set.of("alpha")).contextTokens(50).build());
    final PolicyDecision second =
        engine.govern(request().candidateProviders(Set.of("omega")).contextTokens(50).build());

    assertThat(first.verdict()).isEqualTo(second.verdict());
    assertThat(first.violations()).isEqualTo(second.violations());
  }

  // ---- no store, no socket, no file -----------------------------------------------------------

  @Test
  void noGovernanceClassHoldsAFileHandleSocketOrConnection() throws Exception {
    for (final Class<?> type : governanceClasses()) {
      for (final Field field : type.getDeclaredFields()) {
        assertForbiddenFree(type.getSimpleName() + "." + field.getName(), field.getType());
      }
    }
  }

  @Test
  void noGovernanceMethodTakesOrReturnsAFileSocketOrConnection() throws Exception {
    for (final Class<?> type : governanceClasses()) {
      for (final Method method : type.getDeclaredMethods()) {
        assertForbiddenFree(signature(type, method), method.getReturnType());
        for (final Class<?> parameter : method.getParameterTypes()) {
          assertForbiddenFree(signature(type, method), parameter);
        }
      }
    }
  }

  @Test
  void theStoreIsAReferenceCellRatherThanADatabase() throws Exception {
    for (final Field field : PolicyStore.class.getDeclaredFields()) {
      assertForbiddenFree("PolicyStore." + field.getName(), field.getType());
    }
  }

  // ---- content-free -----------------------------------------------------------------------------

  @Test
  void theAuditRecordIsMarkedContentFree() {
    assertThat(ContentFree.class).isAssignableFrom(PolicyAuditEvent.class);
    assertThat(ContentFree.class).isAssignableFrom(PolicyViolation.class);
  }

  @Test
  void everyContentFreeRecordIsBuiltOnlyFromIdsCodesAndCounts() throws Exception {
    final List<Class<?>> checked = new ArrayList<>();
    for (final Class<?> type : governanceClasses()) {
      if (!ContentFree.class.isAssignableFrom(type) || !type.isRecord()) {
        continue;
      }
      checked.add(type);
      for (final RecordComponent component : type.getRecordComponents()) {
        final Class<?> componentType = component.getType();
        final boolean safe =
            CONTENT_FREE_TYPES.contains(componentType)
                || componentType.isEnum()
                || componentType.getName().startsWith("io.reliabilityai.gateway.canonical.identity")
                || componentType.getName().startsWith(PACKAGE);
        assertThat(safe)
            .as("%s.%s is a %s", type.getSimpleName(), component.getName(), componentType.getName())
            .isTrue();
      }
    }
    assertThat(checked).isNotEmpty();
  }

  @Test
  void aViolationNamesTheStatementWithoutRepeatingWhatTheRequestAskedFor() {
    final var registry = new PolicyRegistry(new PolicyStore(64, 2), new InProcessPolicyMetrics());
    registry.install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("m", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of("model.approved")))));

    final PolicyDecision decision = engineOver(registry).govern(request().build());
    final PolicyViolation violation = decision.binding().orElseThrow();

    // The record says "rule m on the model allow-list refused this", never "the caller asked for
    // model.chat.v1" — which would put the tenant's model inventory into every log line.
    assertThat(violation.toString()).doesNotContain(PolicyFixture.MODEL.value());
    assertThat(decision.reasonCode()).doesNotContain(PolicyFixture.MODEL.value());
  }

  @Test
  void anExternalReasonCodeIsOnlyEverATierName() {
    for (final PolicyType type : PolicyType.values()) {
      final String code = type.denialReason().code();

      assertThat(code).matches("[a-z-]+");
      assertThat(code).doesNotContain("rule").doesNotContain("model.");
    }
  }

  // ---- decision totality ------------------------------------------------------------------------

  @Test
  void everyEnforcementLevelAndVerdictPairingIsAccountedFor() {
    for (final Verdict verdict : Verdict.values()) {
      assertThat(verdict.worst(verdict)).isEqualTo(verdict);
      assertThat(verdict.worst(Verdict.DENY)).isEqualTo(Verdict.DENY);
      assertThat(Verdict.ALLOW.worst(verdict)).isEqualTo(verdict);
    }
  }

  @Test
  void verdictSeverityIsATotalOrderWithDenyAtTheTop() {
    assertThat(Verdict.DENY.worst(Verdict.SOFT_DENY)).isEqualTo(Verdict.DENY);
    assertThat(Verdict.SOFT_DENY.worst(Verdict.DRY_RUN)).isEqualTo(Verdict.SOFT_DENY);
    assertThat(Verdict.DRY_RUN.worst(Verdict.ALLOW)).isEqualTo(Verdict.DRY_RUN);
    assertThat(Verdict.DENY.admits()).isFalse();
  }

  private static GovernanceEngine engineOver(final PolicyRegistry registry) {
    return new GovernanceEngine(
        registry,
        new PolicyEvaluator(Duration.ofSeconds(30), Duration.ofMinutes(5)),
        (PolicyUsagePort) scope -> Optional.of(usage()),
        event -> {},
        io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics.NO_OP,
        CLOCK,
        TickerPort.FROZEN,
        null);
  }

  private static void assertForbiddenFree(final String where, final Class<?> type) {
    final String name = type.getName();
    assertThat(FORBIDDEN_PACKAGES)
        .as("%s is a %s", where, name)
        .noneSatisfy(forbidden -> assertThat(name).startsWith(forbidden));
  }

  private static String signature(final Class<?> owner, final Method method) {
    return owner.getSimpleName() + "." + method.getName() + "()";
  }

  /** Every compiled class in the governance module, found from where this build put them. */
  private static List<Class<?>> governanceClasses()
      throws IOException, URISyntaxException, ClassNotFoundException {
    final Path root =
        Path.of(GovernanceEngine.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    final List<Class<?>> classes = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (final Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
        final String name =
            root.relativize(file)
                .toString()
                .replace(java.io.File.separatorChar, '.')
                .replaceAll("\\.class$", "");
        if (!name.startsWith(PACKAGE) || name.endsWith("package-info")) {
          continue;
        }
        classes.add(Class.forName(name));
      }
    }
    assertThat(classes).as("no governance classes were discovered").isNotEmpty();
    return classes;
  }
}
