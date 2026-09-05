package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.dataplane.app.runtime.GatewayRuntime;
import io.reliabilityai.gateway.dataplane.secrets.adapter.LastKnownGoodCredentialSnapshotCache;
import io.reliabilityai.gateway.dataplane.secrets.adapter.SnapshotCredentialMaterialSource;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * The process entry point: reads the environment, starts one node, and waits.
 *
 * <p>Deliberately thin. Everything about <em>what</em> this node is lives in {@link LaunchWiring};
 * everything about <em>how</em> its components fit together lives in {@link GatewayRuntime}. This
 * class only owns the process lifecycle — start, park, and stop once on the way out.
 *
 * <p>Startup is fail-closed and loud. A node that cannot read its credentials, create its data
 * directory or bind its port exits non-zero with the reason on stderr, rather than reaching a
 * half-wired state that answers health checks and fails real traffic.
 */
public final class GatewayMain {

  private GatewayMain() {}

  /**
   * Starts the node and blocks until the JVM is signalled to stop.
   *
   * @param args ignored; all configuration comes from the environment
   */
  public static void main(final String[] args) {
    try {
      run();
    } catch (final LaunchConfigurationException failure) {
      System.err.println("startup refused: " + failure.getMessage());
      System.err.println();
      usage(System.err);
      Runtime.getRuntime().halt(2);
    } catch (final IOException | RuntimeException failure) {
      System.err.println("startup failed: " + failure);
      failure.printStackTrace(System.err);
      Runtime.getRuntime().halt(1);
    }
  }

  private static void run() throws IOException {
    final LaunchSettings settings = LaunchSettings.fromEnvironment();
    Files.createDirectories(settings.dataDirectory().resolve("wal"));

    final GatewayRuntime runtime = new GatewayRuntime(LaunchWiring.config(settings));
    runtime.start();
    publishProviderCredential(runtime, settings);

    final int port = runtime.httpIngress().orElseThrow().boundPort();
    announce(settings, port);

    final CountDownLatch stopped = new CountDownLatch(1);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  System.out.println("stopping...");
                  runtime.stop();
                  stopped.countDown();
                },
                "gateway-shutdown"));
    try {
      stopped.await();
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Publishes the provider credential into the node's credential snapshot cache.
   *
   * <p>This is the step a control plane performs in a real deployment. Until the credential is
   * published the SECRETS stage has nothing to materialize, and every request would fail at
   * credential resolution rather than at the provider — so it happens before the port is announced
   * as ready to take traffic.
   */
  private static void publishProviderCredential(
      final GatewayRuntime runtime, final LaunchSettings settings) {
    final LastKnownGoodCredentialSnapshotCache cache = runtime.credentialCache();
    cache.applyPublished(
        LaunchWiring.credentialVersion(),
        Map.of(
            new LastKnownGoodCredentialSnapshotCache.Key(
                LaunchWiring.TENANT, LaunchWiring.ROUTE_REF),
            new SnapshotCredentialMaterialSource(
                new CredentialSnapshotRef(
                    LaunchWiring.credentialVersion(),
                    LaunchWiring.TENANT,
                    Instant.now().plusSeconds(86_400L)),
                settings.providerApiKey())));
  }

  private static void announce(final LaunchSettings settings, final int port) {
    final String base = "http://" + settings.bindHost() + ":" + port;
    System.out.printf(
        Locale.ROOT,
        "%nReliability-First AI Gateway is READY%n"
            + "  node        %s%n"
            + "  listening   %s%n"
            + "  model       %s%n"
            + "  provider    %s%n"
            + "  tenant      %s%n%n"
            + "  health      curl %s/health%n"
            + "  ready       curl %s/ready%n"
            + "  metrics     curl %s/metrics%n%n"
            + "Governance decisions print below as AUDIT lines.%n%n",
        settings.nodeId(),
        base,
        settings.model().value(),
        settings.providerBaseUri(),
        LaunchWiring.TENANT,
        base,
        base,
        base);
  }

  private static void usage(final java.io.PrintStream out) {
    out.println("Environment:");
    out.println("  GATEWAY_PROVIDER_API_KEY  (required)  upstream provider credential");
    out.println("  GATEWAY_PROVIDER_BASE_URI (required)  provider API root, scheme and host");
    out.println("  GATEWAY_API_KEY           (required)  bearer token callers must present");
    out.println("  GATEWAY_MODEL             (required)  canonical model this node serves");
    out.println("  GATEWAY_PROVIDER          (optional)  adapter id; only needed if several");
    out.println("  GATEWAY_HOST              (default 127.0.0.1)");
    out.println("  GATEWAY_PORT              (default 8080)");
    out.println("  GATEWAY_NODE_ID           (default gateway-local)");
    out.println("  GATEWAY_DATA_DIR          (default ./data)  write-ahead log, dead-letter file");
  }
}
