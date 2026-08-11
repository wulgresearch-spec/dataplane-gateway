package io.reliabilityai.gateway.canonical.context;

import io.reliabilityai.gateway.canonical.identity.CodeVersion;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * The pinned snapshot context (Doc 33 §10.1). All snapshot versions are pinned at request start
 * (after C6, Doc 32 §SPT) and are immutable for the request (Doc 29 §SCC). The {@code (codeVersion,
 * snapshotVersions)} pair is the recorded identity for replay (Doc 29 §CVR).
 *
 * @param codeVersion the immutable deployable version (AD-020)
 * @param snapshotVersions the pinned snapshot versions keyed by snapshot name
 */
public record SnapshotContext(
    CodeVersion codeVersion, Map<String, SnapshotVersion> snapshotVersions) {

  /** Compact constructor validating fields and defensively copying the version map. */
  public SnapshotContext {
    Preconditions.requireNonNull(codeVersion, "codeVersion");
    // Inlined copy, not Preconditions.immutableMap — see that method's javadoc (EI_EXPOSE_REP).
    snapshotVersions = snapshotVersions == null ? Map.of() : Map.copyOf(snapshotVersions);
  }
}
