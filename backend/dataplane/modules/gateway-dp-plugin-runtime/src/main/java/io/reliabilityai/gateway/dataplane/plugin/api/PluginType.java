package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * The execution substrate a plugin runs on (Doc 28 §ISO).
 *
 * <p>The type determines whether the runtime <em>may</em> run the plugin in the host JVM. Doc 28
 * ISO-1 places the isolation boundary at the OS process for any plugin running untrusted
 * third-party code, so only {@link #INTERNAL} — reserved for first-party code shipped with the
 * gateway — is permitted in-process, and only when the manifest also declares {@link
 * TrustTier#FIRST_PARTY}.
 *
 * <p>{@link #WASM} and {@link #PYTHON} are declared so a manifest naming them is a <em>recognized,
 * refused</em> type rather than an unparseable one. They report {@link #supported()} false: the
 * runtime fails closed on them instead of silently falling back to a weaker substrate.
 */
public enum PluginType {

  /** First-party Java code shipped with the gateway; the only type permitted in the host JVM. */
  INTERNAL(false, true),

  /** An external OS process the runtime spawns and supervises; the untrusted-code default. */
  PROCESS(true, true),

  /** A network-reachable plugin invoked through the mediated host API (Doc 28 PRT-D7). */
  REMOTE(true, true),

  /** A Model Context Protocol server, invoked as a remote plugin over the mediated host API. */
  MCP(true, true),

  /** Reserved: a WebAssembly module. Recognized and refused until a substrate exists. */
  WASM(true, false),

  /** Reserved: an embedded Python interpreter. Recognized and refused until a substrate exists. */
  PYTHON(true, false);

  private final boolean requiresProcessIsolation;
  private final boolean supported;

  PluginType(final boolean requiresProcessIsolation, final boolean supported) {
    this.requiresProcessIsolation = requiresProcessIsolation;
    this.supported = supported;
  }

  /**
   * Whether Doc 28 ISO-1 forbids running this type inside the host JVM.
   *
   * @return true if the plugin must run outside the request path's process
   */
  public boolean requiresProcessIsolation() {
    return requiresProcessIsolation;
  }

  /**
   * Whether a substrate for this type exists today.
   *
   * @return true if the runtime can execute this type; false means registration is refused
   */
  public boolean supported() {
    return supported;
  }
}
