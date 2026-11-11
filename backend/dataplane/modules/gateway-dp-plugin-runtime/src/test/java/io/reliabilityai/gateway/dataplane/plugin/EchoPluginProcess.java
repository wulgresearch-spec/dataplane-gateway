package io.reliabilityai.gateway.dataplane.plugin;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * A real child-process plugin, spawned by the process-sandbox tests.
 *
 * <p>Speaks the {@code ProcessPluginAdapter} wire protocol: one request line in, one response line
 * out. The first argument selects the behaviour — {@code sleep} to hang past a deadline, {@code
 * crash} to die without replying, anything else to echo. Those two misbehaviours are the failure
 * modes the process boundary exists to contain.
 */
public final class EchoPluginProcess {

  private EchoPluginProcess() {}

  /**
   * Entry point for the spawned child.
   *
   * @param args the behaviour selector
   * @throws Exception if the child cannot read its request
   */
  public static void main(final String[] args) throws Exception {
    final String mode = args.length > 0 ? args[0] : "echo";
    if ("crash".equals(mode)) {
      System.exit(3);
    }

    final BufferedReader reader =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    final String line = reader.readLine();
    if (line == null) {
      System.exit(4);
    }

    if ("sleep".equals(mode)) {
      Thread.sleep(600_000L); // outlives any test deadline; the sandbox must kill this
    }

    final String[] parts = line.split("\t", -1);
    if (parts.length < 3 || !"INVOKE".equals(parts[0])) {
      writeReply("ERR\tmalformed-request");
      return;
    }
    if ("fail".equals(mode)) {
      writeReply("ERR\tplugin-said-no");
      return;
    }

    final String arguments =
        new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
    final String output = "child-echo:" + parts[1] + ":" + arguments;
    writeReply(
        "OK\t" + Base64.getEncoder().encodeToString(output.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * The child writes a protocol reply, not a log message. Access the standard output descriptor
   * directly to keep the global ban on System.out logging intact.
   */
  private static void writeReply(final String line) throws IOException {
    try (Writer stdout =
        new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), StandardCharsets.UTF_8)) {
      stdout.write(line);
      stdout.write('\n');
      stdout.flush();
    }
  }
}
