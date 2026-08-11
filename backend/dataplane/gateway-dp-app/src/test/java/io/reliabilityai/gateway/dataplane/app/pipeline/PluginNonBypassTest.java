package io.reliabilityai.gateway.dataplane.app.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards Doc 28 PRT-D1 — <b>"removing all plugins changes no mandatory-stage outcome"</b> —
 * structurally.
 *
 * <p>A behavioural test can only demonstrate this for the plugins it happens to bind. The property
 * has to hold for <em>every</em> plugin anyone ever writes, and the only way to get that is to
 * prove the contribution has nowhere to go: {@link ExtensionOutcome} must be read in exactly one
 * place in the pipeline, and that place must be the trace.
 *
 * <p>This matters more than it looks. Wiring plugins into the request path is the single change
 * most likely to erode AD-018, and it erodes by someone reasonably deciding to let one useful
 * signal influence one decision. The scan turns that from a judgement call into a failing build.
 *
 * <p>The complementary behavioural evidence is that the entire pre-existing suite passed unchanged
 * when the five points were wired in — which is PRT-D1 demonstrated on a node with no plugins
 * bound.
 */
class PluginNonBypassTest {

  /** Mandatory-stage collaborators a contribution must never reach. */
  private static final List<String> MANDATORY_COLLABORATORS =
      List.of(
          "governance",
          "admission",
          "router",
          "secrets",
          "reliability",
          "streamGuard",
          "schemaLock",
          "metering",
          "publisher");

  private static Path pipelineSource() {
    Path candidate = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && candidate != null; depth++) {
      final Path source =
          candidate.resolve(
              "dataplane/gateway-dp-app/src/main/java/io/reliabilityai/gateway/dataplane/app/"
                  + "pipeline/RequestPipeline.java");
      if (Files.isRegularFile(source)) {
        return source;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("RequestPipeline source not found");
  }

  private static String pipelineCode() throws IOException {
    return Files.readString(pipelineSource(), StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("(?m)//.*$", " ");
  }

  @Test
  void everyFrozenExtensionPointIsDispatchedOnTheRequestPath() throws IOException {
    final String code = pipelineCode();

    // The gap this milestone closed: the runtime was fully built and never called, so every plugin
    // on
    // every node was dead code. Doc 28 §2 requires dispatch "at each frozen extension point".
    for (final ExtensionPoint point : ExtensionPoint.values()) {
      assertThat(code)
          .as("%s must be dispatched by the pipeline", point)
          .contains("ExtensionPoint." + point.name());
    }
  }

  @Test
  void aContributionIsReadInExactlyOnePlaceAndThatPlaceIsTheTrace() throws IOException {
    final String code = pipelineCode();

    // Every dispatch feeds recordExtension and nothing else. If a call ever appears whose result is
    // assigned to a variable a stage could read, this count changes and the test fails.
    final int dispatches = count(code, Pattern.compile("extensions\\.dispatch\\("));
    final int recorded = count(code, Pattern.compile("recordExtension\\(\\s*trace,"));

    assertThat(dispatches).isEqualTo(ExtensionPoint.values().length);
    assertThat(recorded).isEqualTo(dispatches);
  }

  @Test
  void noMandatoryCollaboratorEverReceivesAnExtensionOutcome() throws IOException {
    final String code = pipelineCode();

    // A contribution reaching any of these would make a plugin able to influence a mandatory
    // decision,
    // which is precisely the bypass AD-018 and Doc 28 PRT-D1 forbid.
    for (final String collaborator : MANDATORY_COLLABORATORS) {
      final Pattern handoff =
          Pattern.compile(
              collaborator + "\\.[a-zA-Z]+\\([^)]*\\b(outcome|contribution|extensionOutcome)\\b",
              Pattern.CASE_INSENSITIVE);
      assertThat(handoff.matcher(code).find())
          .as("%s must not receive a plugin contribution", collaborator)
          .isFalse();
    }
  }

  @Test
  void theExtensionOutcomeTypeIsNeverStoredOnTheRequestCarrier() throws IOException {
    final Path carrier = pipelineSource().getParent().resolve("RequestExecution.java");
    final String code = Files.readString(carrier, StandardCharsets.UTF_8);

    // RequestExecution is threaded through every stage. An ExtensionOutcome parked on it would be
    // reachable by all of them, which is the same bypass by a slower route.
    assertThat(code.toLowerCase(Locale.ROOT)).doesNotContain("extensionoutcome");
  }

  @Test
  void theMandatoryStageTraceCannotBePollutedByAPlugin() throws IOException {
    final Path trace = pipelineSource().getParent().resolve("StageTrace.java");
    final String code = Files.readString(trace, StandardCharsets.UTF_8);

    // Extension entries live in their own list. If they shared the mandatory list, the non-bypass
    // assertion "every mandatory stage ran exactly once, in order" would depend on which plugins a
    // node happens to have bound — a plugin could change the non-bypass evidence.
    assertThat(code).contains("private final List<ExtensionEntry> extensions");
    assertThat(code).contains("extensions.add(new ExtensionEntry(point, detail))");
    assertThat(code).doesNotContain("entries.add(new ExtensionEntry");
  }

  @Test
  void theStageTraceStillReportsOnlyMandatoryStages() {
    final StageTrace trace = new StageTrace();
    trace.record(MandatoryStage.INGRESS, StageTrace.Disposition.EXECUTED, 0L, "admitted");
    trace.recordExtension(ExtensionPoint.CLASSIFICATION, "plugins=3 isolated=1");

    assertThat(trace.stages()).containsExactly(MandatoryStage.INGRESS);
    assertThat(trace.entries()).hasSize(1);
    assertThat(trace.extensions()).hasSize(1);
  }

  @Test
  void noForbiddenExtensionPointIsDispatchedAnywhere() throws IOException {
    final String code = pipelineCode().toLowerCase(Locale.ROOT);

    // Doc 28 EPC-3 names these as forbidden. They would bypass StreamGuard, SchemaLock or the
    // Provider
    // Adapter, which is why tool execution has no legal home on this path today.
    for (final String forbidden :
        List.of(
            "post_routing",
            "pre_invoke",
            "post_invoke",
            "response_transform",
            "provider_interception",
            "stream_mutation")) {
      assertThat(code).as("EPC-3 forbids %s", forbidden).doesNotContain(forbidden);
    }
  }

  private static int count(final String code, final Pattern pattern) {
    final Matcher matcher = pattern.matcher(code);
    int found = 0;
    while (matcher.find()) {
      found++;
    }
    return found;
  }
}
