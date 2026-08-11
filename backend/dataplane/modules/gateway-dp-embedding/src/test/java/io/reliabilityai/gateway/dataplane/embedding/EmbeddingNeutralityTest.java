package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule that makes provider neutrality structural rather than aspirational.
 *
 * <p>Every other test in this module can pass while the module quietly acquires a vendor dependency
 * — a {@code switch} on a provider id in the pipeline, a model name in the normalizer, an endpoint
 * in a constant. Each individual instance looks reasonable at the time, which is exactly why it
 * needs a mechanical check rather than review discipline.
 *
 * <p>The bar here is stricter than "vendors only in the adapter package". This module contains
 * <b>no vendor adapter at all</b>: the OpenAI embeddings adapter lives in {@code
 * dataplane/providers/gateway-provider-openai}, because the repository-wide {@code
 * ProviderNeutralityTest} forbids a vendor name in any production source outside {@code
 * dataplane/providers}. So the assertion below is over the whole module, with no exempt corner.
 *
 * <p>These checks read source rather than bytecode, because the thing forbidden is a <em>name</em>,
 * and a name survives into a string constant, a class name and an import equally.
 */
@DisplayName("provider neutrality")
final class EmbeddingNeutralityTest {

  /** The relative path from a module root down to this package's main sources. */
  private static final String FROM_MODULE_ROOT =
      "src/main/java/io/reliabilityai/gateway/dataplane/embedding";

  /**
   * Names that must not appear in this module at all.
   *
   * <p>Deliberately includes vendors this gateway does not serve. A check that only forbade the one
   * vendor already integrated would pass right up until someone added the second one badly.
   */
  private static final List<String> VENDOR_NAMES =
      List.of(
          "openai",
          "anthropic",
          "claude",
          "gemini",
          "voyage",
          "cohere",
          "jina",
          "nomic",
          "mistral",
          "bedrock",
          "vertex",
          "azure",
          "huggingface");

  /**
   * Locates this module's main sources whatever directory the test was launched from.
   *
   * <p>A hard-coded relative path would make these checks pass or fail depending on the runner's
   * working directory — Maven starts in the module, the local javac harness starts in {@code
   * backend} — and a neutrality check that silently walks nothing is worse than no check, because
   * {@code assertThat(offenders).isEmpty()} passes loudest when it inspected no files. The file
   * count is asserted below for the same reason.
   *
   * @return the package source directory
   */
  private static Path moduleSource() {
    for (Path base = Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
      final Path direct = base.resolve(FROM_MODULE_ROOT);
      if (Files.isDirectory(direct)) {
        return direct;
      }
      final Path nested =
          base.resolve("dataplane/modules/gateway-dp-embedding").resolve(FROM_MODULE_ROOT);
      if (Files.isDirectory(nested)) {
        return nested;
      }
    }
    throw new AssertionError(
        "cannot locate the embedding module sources from " + Path.of("").toAbsolutePath());
  }

  /**
   * Every main source file in this module.
   *
   * @return the source files
   * @throws IOException when the tree cannot be walked
   */
  private static List<Path> sources() throws IOException {
    try (Stream<Path> files = Files.walk(moduleSource())) {
      return files.filter(path -> path.toString().endsWith(".java")).toList();
    }
  }

  @Test
  @DisplayName("the scan actually reaches the module's sources")
  void theScanActuallyReachesTheModulesSources() throws IOException {
    // The guard on the three checks below. Each of them asserts a list is empty, and an empty list
    // is exactly what a scan of nothing produces.
    assertThat(sources()).hasSizeGreaterThan(15);
  }

  @Test
  @DisplayName("no vendor name appears anywhere in the module")
  void noVendorNameAppearsAnywhereInTheModule() throws IOException {
    final List<String> offenders = new ArrayList<>();
    for (final Path file : sources()) {
      final String body = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      for (final String vendor : VENDOR_NAMES) {
        if (body.contains(vendor)) {
          offenders.add(file.getFileName() + " mentions " + vendor);
        }
      }
    }

    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("no endpoint appears anywhere in the module")
  void noEndpointAppearsAnywhereInTheModule() throws IOException {
    final List<String> offenders = new ArrayList<>();
    for (final Path file : sources()) {
      if (Files.readString(file, StandardCharsets.UTF_8).contains("https://")) {
        offenders.add(file.getFileName().toString());
      }
    }

    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("no network type is referenced anywhere in the module")
  void noNetworkTypeIsReferencedAnywhereInTheModule() throws IOException {
    final List<String> offenders = new ArrayList<>();
    for (final Path file : sources()) {
      final String body = Files.readString(file, StandardCharsets.UTF_8);
      for (final String type : List.of("java.net.", "HttpClient", "Socket", "URLConnection")) {
        if (body.contains(type)) {
          offenders.add(file.getFileName() + " references " + type);
        }
      }
    }

    // The module reaches a network only through EmbeddingTransportPort, which is what makes every
    // path in it testable without one — and what makes "no network outside provider adapters" a
    // property of the code rather than a promise in a document.
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("no vector-index vocabulary appears, because B25 does not build one")
  void noVectorIndexVocabularyAppearsBecauseB25DoesNotBuildOne() throws IOException {
    final List<String> offenders = new ArrayList<>();
    for (final Path file : sources()) {
      final String body = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      for (final String forbidden : List.of("hnsw", "faiss", "annoy", "cosinesimilarity", "topk")) {
        if (body.contains(forbidden)) {
          offenders.add(file.getFileName() + " mentions " + forbidden);
        }
      }
    }

    // The scope line, enforced. This milestone generates embeddings; indexing, approximate nearest
    // neighbour, similarity and retrieval are B26 and later. A half-built index landing here under
    // cover of "just a helper" is the way that boundary usually erodes.
    assertThat(offenders).isEmpty();
  }

  @Test
  @DisplayName("the request type never renders the text it carries")
  void theRequestTypeNeverRendersTheTextItCarries() {
    // Neutrality's sibling: the inputs are tenant content, often the same content the PII engine
    // has
    // just classified as sensitive, and a request logged in full would undo that.
    assertThat(
            new EmbeddingRequest(
                    ACME, MODEL, List.of("secret content"), EmbeddingRequest.EmbeddingPurpose.WRITE)
                .toString())
        .doesNotContain("secret content")
        .contains("inputs=1");
  }
}
