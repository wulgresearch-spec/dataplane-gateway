package io.reliabilityai.gateway.dataplane.plugin.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginHealth;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolContext;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Runs a {@link io.reliabilityai.gateway.dataplane.plugin.api.PluginType#PROCESS} plugin as a child
 * process (Doc 28 ISO-1, §36.1).
 *
 * <p><b>One process per invocation.</b> Doc 28 §36.1 asks for a stateless host with no plugin state
 * surviving across requests or tenants, and the cheapest way to guarantee that is to have nowhere
 * to put it: a fresh process starts, serves exactly one call, and dies. Pooling processes would be
 * faster and would reintroduce precisely the cross-request state the contract forbids. The cost is
 * real and is stated in the milestone's performance notes.
 *
 * <p><b>The wire format is deliberately dull.</b> One request line in, one response line out,
 * payloads base64-encoded so no argument or output can contain the delimiter. A structured protocol
 * would need a parser on the host side fed by untrusted bytes, which is a bigger attack surface
 * than this problem justifies.
 *
 * <p><b>The child's environment is scrubbed.</b> {@code ProcessBuilder} inherits the parent's
 * environment by default, which for this process includes whatever the operator exported —
 * plausibly including credentials. That is cleared and rebuilt from the manifest's explicit grants
 * only, so Doc 28 PRT-D8's "plugins never touch credentials" survives contact with the OS.
 */
public final class ProcessPluginAdapter implements ToolPlugin {

  /** Sent to the child; the child answers with a single response line. */
  private static final String COMMAND_INVOKE = "INVOKE";

  /** The child's success prefix. */
  private static final String REPLY_OK = "OK";

  /** The longest response line accepted, bounding what a child can make the host buffer. */
  private static final int MAX_REPLY_LINE = 8 * 1024 * 1024;

  private final PluginManifest manifest;
  private final List<String> command;
  private final ProcessSandbox sandbox;
  private volatile boolean started;

  /**
   * Creates the adapter.
   *
   * @param manifest the verified manifest
   * @param command the command line that starts one child, already resolved by the operator
   * @param sandbox the process substrate, which owns kill-on-deadline
   */
  public ProcessPluginAdapter(
      final PluginManifest manifest, final List<String> command, final ProcessSandbox sandbox) {
    this.manifest = Preconditions.requireNonNull(manifest, "manifest");
    this.command = List.copyOf(Preconditions.requireNonEmpty(command, "command"));
    this.sandbox = Preconditions.requireNonNull(sandbox, "sandbox");
  }

  @Override
  public PluginManifest manifest() {
    return manifest;
  }

  @Override
  public void start(final ToolContext context) {
    // Nothing to start: the process model spawns per invocation, so there is no long-lived child to
    // bring up here. Marking started is what makes a stopped adapter refuse work.
    started = true;
  }

  @Override
  public void stop() {
    started = false;
  }

  @Override
  public PluginHealth health() {
    // Honest: with no resident child there is nothing to ask. A probe would have to spawn a
    // process,
    // and a health check that spawns a process is a load generator.
    return started ? PluginHealth.UNKNOWN : PluginHealth.FAILED;
  }

  @Override
  public ToolResponse invoke(final ToolInvocation invocation) throws Exception {
    Preconditions.requireNonNull(invocation, "invocation");
    if (!started) {
      throw new IllegalStateException("process plugin not started");
    }

    final ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(false);
    // The child's stderr is discarded rather than read. It is untrusted text that could contain
    // anything the plugin was processing, and reading it would only create somewhere for that
    // content
    // to end up (Doc 14 §7.1).
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    applyEnvironment(builder);

    final Process child = builder.start();
    sandbox.bind(invocation.context().correlationId(), child);
    try {
      try (Writer stdin = new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8)) {
        stdin.write(COMMAND_INVOKE);
        stdin.write('\t');
        stdin.write(invocation.toolName());
        stdin.write('\t');
        stdin.write(
            Base64.getEncoder()
                .encodeToString(invocation.request().arguments().getBytes(StandardCharsets.UTF_8)));
        stdin.write('\n');
        stdin.flush();
      }

      final String reply = readReplyLine(child);
      if (reply == null) {
        // The child closed its output without answering — it crashed, or exited without replying.
        throw new IOException("plugin process produced no reply");
      }
      final int firstTab = reply.indexOf('\t');
      final String status = firstTab < 0 ? reply : reply.substring(0, firstTab);
      if (!REPLY_OK.equals(status)) {
        // The child's own error code is not forwarded: it is untrusted text. The classifier turns
        // this
        // into a content-free PLUGIN_BUG.
        throw new IllegalStateException("plugin process reported failure");
      }
      final String encoded = firstTab < 0 ? "" : reply.substring(firstTab + 1);
      final String output = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
      return ToolResponse.of(output);
    } finally {
      // Unbind before destroying, so a concurrent deadline breach cannot race this cleanup and kill
      // a
      // process id the OS may already have reused.
      sandbox.unbind(invocation.context().correlationId());
      child.destroyForcibly();
    }
  }

  /**
   * Reads a single bounded reply line.
   *
   * <p>Bounded on purpose: a child that emits an endless line would otherwise grow host memory
   * without ever tripping a wall-clock deadline, because it is making steady progress the whole
   * time.
   *
   * <p>A trailing carriage return is stripped. A plugin author writing the obvious {@code
   * System.out.println} gets a platform line separator, which on Windows is {@code \r\n} — and a
   * stray {@code \r} riding along on the payload corrupts the base64 decode into what looks like a
   * plugin bug. Accepting both endings costs nothing and removes an entire class of
   * works-on-my-machine plugin.
   */
  private static String readReplyLine(final Process child) throws IOException {
    final BufferedReader reader =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
    final StringBuilder line = new StringBuilder();
    int character;
    while ((character = reader.read()) >= 0) {
      if (character == '\n') {
        return stripCarriageReturn(line);
      }
      line.append((char) character);
      if (line.length() > MAX_REPLY_LINE) {
        throw new IOException("plugin process reply exceeded the accepted length");
      }
    }
    return line.isEmpty() ? null : stripCarriageReturn(line);
  }

  private static String stripCarriageReturn(final StringBuilder line) {
    if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
      line.setLength(line.length() - 1);
    }
    return line.toString();
  }

  /** Clears the inherited environment and re-adds only what the manifest explicitly granted. */
  private void applyEnvironment(final ProcessBuilder builder) {
    final Map<String, String> environment = builder.environment();
    final List<String> inherited = new ArrayList<>(environment.keySet());
    for (final String key : inherited) {
      environment.remove(key);
    }
    for (final String granted : manifest.permissions().environmentKeys()) {
      final String value = System.getenv(granted);
      if (value != null) {
        environment.put(granted, value);
      }
    }
  }
}
