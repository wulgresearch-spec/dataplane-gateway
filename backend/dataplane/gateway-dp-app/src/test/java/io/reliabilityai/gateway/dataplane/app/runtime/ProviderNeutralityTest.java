package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The guard on the whole point of provider abstraction: <b>no provider name appears in production
 * code outside a provider module</b>.
 *
 * <p>Every other test here can pass while the gateway quietly re-acquires a vendor dependency — a
 * {@code switch} on a provider id in the router, an {@code if} in the pipeline, a hard-coded
 * endpoint in the composition root. That erosion is gradual and each individual instance looks
 * reasonable, which is exactly why it needs a mechanical check rather than review discipline.
 * Before this milestone the composition root constructed one vendor's configuration, HTTP client,
 * transport, health check and translator by name; this test is what stops that returning.
 *
 * <p>Scans source rather than bytecode because the thing being forbidden is a <em>name</em>, and a
 * name survives into a string constant, a class name and an import equally. Only {@code
 * src/main/java} is scanned: test code may name providers freely — someone has to construct the
 * reference module, and a fixture that could not would be testing nothing.
 *
 * <p><b>Comments are stripped before scanning, deliberately.</b> The forbidden thing is code that
 * knows a vendor, not prose that mentions one. StreamGuard's framing enum documents itself with
 * "decodes 'SSE,' never 'OpenAI'" — that comment states the neutrality rule, and a check that
 * flagged it would pressure people into writing worse documentation to appease a linter. Matching
 * is on whole words for the same reason: "replicated" is not Replicate and "coherent" is not
 * Cohere.
 */
class ProviderNeutralityTest {

  /**
   * Vendor names that must not appear outside a provider module.
   *
   * <p>Deliberately includes vendors this gateway does not support. A check that only forbade the
   * one vendor already integrated would pass right up until someone added the second one badly.
   */
  private static final List<String> VENDOR_NAMES =
      List.of(
          "openai",
          "anthropic",
          "claude",
          "gemini",
          "bedrock",
          "vertex",
          "cohere",
          "mistral",
          "azure",
          "huggingface",
          "replicate");

  /** Where provider-specific code is allowed to live. */
  private static final String PROVIDER_MODULE_ROOT =
      "dataplane" + java.io.File.separator + "providers";

  @Test
  void noProductionSourceOutsideAProviderModuleNamesAVendor() throws IOException {
    final Path backend = backendRoot();
    final List<String> offenders = new ArrayList<>();

    for (final Path source : productionSources(backend)) {
      if (source.toString().contains(PROVIDER_MODULE_ROOT)) {
        continue;
      }
      final String code = codeOf(source);
      for (final String vendor : VENDOR_NAMES) {
        if (namesVendor(code, vendor)) {
          offenders.add(backend.relativize(source) + " names '" + vendor + "'");
        }
      }
    }

    assertThat(offenders)
        .as("provider names must not appear outside dataplane/providers")
        .isEmpty();
  }

  @Test
  void theCompositionRootDoesNotNameAVendor() throws IOException {
    final Path runtime =
        backendRoot()
            .resolve(
                "dataplane/gateway-dp-app/src/main/java/io/reliabilityai/gateway/dataplane/app/runtime");

    for (final Path source : sourcesUnder(runtime)) {
      final String code = codeOf(source);
      // Called out separately from the sweep above because this is the file that was wrong: wiring
      // a
      // provider used to mean editing the class that wires the entire gateway.
      for (final String vendor : VENDOR_NAMES) {
        assertThat(namesVendor(code, vendor))
            .as("%s names '%s'", source.getFileName(), vendor)
            .isFalse();
      }
    }
  }

  @Test
  void theProviderModulesAreWhereVendorCodeActuallyLives() throws IOException {
    final Path providers = backendRoot().resolve("dataplane/providers");

    // The complement of the rule above: if nothing named a vendor anywhere, the scan would be
    // vacuously green and would keep passing after the provider was deleted.
    assertThat(sourcesUnder(providers)).isNotEmpty();
    final boolean anyNamesAVendor =
        sourcesUnder(providers).stream()
            .anyMatch(
                source -> {
                  final String code = codeOf(source);
                  return VENDOR_NAMES.stream().anyMatch(vendor -> namesVendor(code, vendor));
                });
    assertThat(anyNamesAVendor).isTrue();
  }

  @Test
  void everyRoutingAndGovernanceDecisionIsKeyedOnSomethingOpaque() throws IOException {
    final Path backend = backendRoot();
    final List<Path> decisionModules =
        List.of(
            backend.resolve("dataplane/modules/gateway-dp-router/src/main/java"),
            backend.resolve("dataplane/modules/gateway-dp-governance-engine/src/main/java"),
            backend.resolve("dataplane/modules/gateway-dp-reliability/src/main/java"));

    for (final Path module : decisionModules) {
      for (final Path source : sourcesUnder(module)) {
        final String code = codeOf(source);
        for (final String vendor : VENDOR_NAMES) {
          assertThat(namesVendor(code, vendor))
              .as(
                  "%s must decide from capabilities, not vendor identity",
                  backend.relativize(source))
              .isFalse();
        }
      }
    }
  }

  /**
   * A file's code with comments removed and lower-cased.
   *
   * <p>Block comments first, then line comments. Crude by the standards of a Java parser and
   * exactly right for this job: the only way to defeat it is to hide a vendor name in a string
   * containing {@code //}, which no plausible accident produces.
   */
  private static String codeOf(final Path source) {
    final String body;
    try {
      body = Files.readString(source, StandardCharsets.UTF_8);
    } catch (final IOException unreadable) {
      return "";
    }
    return BLOCK_COMMENT
        .matcher(body)
        .replaceAll(" ")
        .replaceAll("(?m)//.*$", " ")
        .toLowerCase(Locale.ROOT);
  }

  private static final java.util.regex.Pattern BLOCK_COMMENT =
      java.util.regex.Pattern.compile("/\\*.*?\\*/", java.util.regex.Pattern.DOTALL);

  /** Whether the code names a vendor as a whole word, so "replicated" is not Replicate. */
  private static boolean namesVendor(final String code, final String vendor) {
    return java.util.regex.Pattern.compile("\\b" + vendor + "\\b").matcher(code).find();
  }

  /** Every {@code src/main/java} file in the backend. */
  private static List<Path> productionSources(final Path backend) throws IOException {
    final String mainJava =
        java.io.File.separator
            + "src"
            + java.io.File.separator
            + "main"
            + java.io.File.separator
            + "java";
    try (Stream<Path> files = Files.walk(backend)) {
      return files
          .filter(path -> path.toString().endsWith(".java"))
          .filter(path -> path.toString().contains(mainJava))
          .toList();
    }
  }

  private static List<Path> sourcesUnder(final Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }

  /**
   * Finds the {@code backend} directory by walking up from the working directory.
   *
   * <p>Works under Maven, where the working directory is the module, and under a direct runner,
   * where it is the backend root itself.
   */
  private static Path backendRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    for (int depth = 0; depth < 8 && candidate != null; depth++) {
      if (Files.isDirectory(candidate.resolve("dataplane/providers"))
          && Files.isDirectory(candidate.resolve("libs"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("backend root not found from " + Path.of("").toAbsolutePath());
  }
}
