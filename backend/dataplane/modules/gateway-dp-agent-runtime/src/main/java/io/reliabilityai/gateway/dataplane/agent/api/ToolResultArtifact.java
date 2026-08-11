package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * What a {@link StepKind#PLUGIN} step produces (AD-025 AGT-9).
 *
 * <p><b>This type exists to make one bypass structurally impossible.</b> A tool's output is not
 * handed to a model; it becomes an artifact in the run history, and a model sees it only when a
 * later {@link StepKind#PIPELINE} step names the artifact as an input — which is a new, fully
 * governed {@code RequestPipeline} execution. There is no method here that injects anything
 * anywhere, and there is no path in this runtime from a tool's response to a model's context that
 * skips a turn.
 *
 * <p>Artifacts are <b>tainted by default</b>. A tool's output is untrusted content by construction:
 * it may be a web page, a file, or a record written by a previous injection. AD-024 §35 tracks
 * taint inside a session; carrying the flag on the artifact is what lets it survive the gap
 * <em>between</em> sessions, which is where AD-025 §60.2's run-level Rule of Two is enforced.
 *
 * @param artifactId the artifact's identity, derived from the producing step
 * @param producedBy the step that produced it
 * @param capability the capability the producing step exercised, not a plugin identity
 * @param contentDigest a stable digest of the content, used for replay comparison and audit
 * @param content the recorded content, bounded by the executor before it reaches here
 * @param tainted whether the content is untrusted; true unless the tool is declared trusted
 */
public record ToolResultArtifact(
    String artifactId,
    StepId producedBy,
    String capability,
    String contentDigest,
    String content,
    boolean tainted) {

  /**
   * Validates the artifact.
   *
   * @param artifactId the artifact identity
   * @param producedBy the producing step
   * @param capability the exercised capability
   * @param contentDigest the content digest
   * @param content the recorded content
   * @param tainted the taint flag
   */
  public ToolResultArtifact {
    Preconditions.requireNonBlank(artifactId, "artifactId");
    Preconditions.requireNonNull(producedBy, "producedBy");
    Preconditions.requireNonBlank(capability, "capability");
    Preconditions.requireNonBlank(contentDigest, "contentDigest");
    Preconditions.requireNonNull(content, "content");
  }

  /**
   * Creates a tainted artifact for a step's tool output.
   *
   * @param producedBy the producing step
   * @param capability the exercised capability
   * @param content the tool's recorded output
   * @param contentDigest the digest of that output
   * @return the artifact, marked tainted
   */
  public static ToolResultArtifact tainted(
      final StepId producedBy,
      final String capability,
      final String content,
      final String contentDigest) {
    return new ToolResultArtifact(
        producedBy.value() + ":" + capability,
        producedBy,
        capability,
        contentDigest,
        content,
        true);
  }

  /**
   * Creates an artifact from a source declared trusted by policy.
   *
   * <p>Separate factory rather than a boolean parameter, so that producing an untainted artifact is
   * always a deliberate, greppable act rather than a flag someone flipped.
   *
   * @param producedBy the producing step
   * @param capability the exercised capability
   * @param content the tool's recorded output
   * @param contentDigest the digest of that output
   * @return the artifact, marked untainted
   */
  public static ToolResultArtifact trusted(
      final StepId producedBy,
      final String capability,
      final String content,
      final String contentDigest) {
    return new ToolResultArtifact(
        producedBy.value() + ":" + capability,
        producedBy,
        capability,
        contentDigest,
        content,
        false);
  }
}
