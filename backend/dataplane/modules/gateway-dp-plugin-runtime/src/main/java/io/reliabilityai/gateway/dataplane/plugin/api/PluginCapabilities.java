package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;
import java.util.TreeSet;

/**
 * The closed set of host capabilities a plugin may request (Doc 28 §31, PRT-D5).
 *
 * <p>The capability vocabulary is fixed here rather than left open, because an open vocabulary is
 * how a forbidden capability arrives under a new name. {@link #FORBIDDEN} names the capabilities
 * Doc 28 excludes outright — provider access, stream writes, secrets, pipeline re-entry — and a
 * manifest requesting any of them is refused at verification rather than denied later at call time.
 *
 * @param requested the capabilities the vetted manifest requests
 */
public record PluginCapabilities(Set<String> requested) {

  /** Read the request's content-free classification signal. */
  public static final String READ_CLASSIFICATION = "read.classification";

  /** Read content-free routing hints (model id, region, required capabilities). */
  public static final String READ_ROUTING_HINTS = "read.routing-hints";

  /** Read the request's tenant scope. */
  public static final String READ_TENANT_SCOPE = "read.tenant-scope";

  /** Emit content-free telemetry through the host (Doc 27). */
  public static final String EMIT_TELEMETRY = "emit.telemetry";

  /** Reach a granted host through the mediated network API (Doc 28 PRT-D7). */
  public static final String NETWORK_EGRESS = "net.egress";

  /** Read a granted filesystem prefix through the mediated file API. */
  public static final String FILESYSTEM_READ = "fs.read";

  /** Read a granted environment variable. */
  public static final String ENVIRONMENT_READ = "env.read";

  /** Expose tool definitions the runtime may invoke. */
  public static final String TOOL_EXECUTION = "tool.execute";

  /** Emit an event stream from a tool invocation. */
  public static final String TOOL_STREAMING = "tool.stream";

  /**
   * The capabilities Doc 28 forbids at any level. A manifest naming one is refused (Doc 28 §31.1,
   * EPC-8, PRT-D8, §30.1) — these exist so the refusal is explicit and testable rather than
   * implicit in the absence of an implementation.
   */
  public static final Set<String> FORBIDDEN =
      Set.of(
          "provider.invoke",
          "provider.intercept",
          "stream.write",
          "stream.mutate",
          "response.transform",
          "secret.read",
          "secret.materialize",
          "pipeline.reenter",
          "stage.override");

  /** The capabilities the runtime knows how to grant. */
  public static final Set<String> KNOWN =
      Set.of(
          READ_CLASSIFICATION,
          READ_ROUTING_HINTS,
          READ_TENANT_SCOPE,
          EMIT_TELEMETRY,
          NETWORK_EGRESS,
          FILESYSTEM_READ,
          ENVIRONMENT_READ,
          TOOL_EXECUTION,
          TOOL_STREAMING);

  /** Compact constructor taking a sorted, immutable copy. */
  public PluginCapabilities {
    final Set<String> copy = new TreeSet<>();
    if (requested != null) {
      for (final String capability : requested) {
        copy.add(Preconditions.requireNonBlank(capability, "capability"));
      }
    }
    requested = Set.copyOf(copy);
  }

  /**
   * The empty capability set.
   *
   * @return capabilities granting nothing
   */
  public static PluginCapabilities none() {
    return new PluginCapabilities(Set.of());
  }

  /**
   * Creates a capability set from explicit names.
   *
   * @param capabilities the requested capability names
   * @return the capability set
   */
  public static PluginCapabilities of(final String... capabilities) {
    return new PluginCapabilities(Set.of(capabilities));
  }

  /**
   * Whether the plugin holds the capability.
   *
   * @param capability the capability name
   * @return true if requested and granted
   */
  public boolean has(final String capability) {
    return requested.contains(capability);
  }

  /**
   * The forbidden capabilities this set requests, in deterministic order.
   *
   * @return the forbidden names present, empty when the set is clean
   */
  public Set<String> forbidden() {
    final Set<String> found = new TreeSet<>();
    for (final String capability : requested) {
      if (FORBIDDEN.contains(capability)) {
        found.add(capability);
      }
    }
    return Set.copyOf(found);
  }

  /**
   * The requested capabilities the runtime does not recognize.
   *
   * <p>An unknown capability is refused rather than ignored: silently dropping it would let a
   * manifest claim a privilege the runtime never actually enforces, and the plugin would run
   * believing it had been granted something.
   *
   * @return the unrecognized names, empty when every name is known
   */
  public Set<String> unknown() {
    final Set<String> found = new TreeSet<>();
    for (final String capability : requested) {
      if (!KNOWN.contains(capability) && !FORBIDDEN.contains(capability)) {
        found.add(capability);
      }
    }
    return Set.copyOf(found);
  }
}
