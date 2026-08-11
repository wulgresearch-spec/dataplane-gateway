package io.reliabilityai.gateway.dataplane.app.runtime;

/**
 * The transport ingress seam, expressed purely as a lifecycle the composition root owns (Doc 30).
 *
 * <p>This is deliberately <b>not</b> a server: it says nothing about HTTP, ports, TLS or framing.
 * It is the two lifecycle edges the runtime must control to make its own guarantees hold — the
 * ingress starts accepting only after the pipeline is assembled and the WAL is replayed, and stops
 * accepting <em>first</em> during shutdown so the broker drains against a closed door rather than a
 * moving target. Without that ordering, "graceful shutdown" would race incoming work.
 *
 * <p>An implementation is supplied by whatever actually terminates client connections. Until one is
 * wired, {@code INGRESS} stays unbound and the node fails closed at the activation gate rather than
 * pretending it can serve traffic.
 */
public interface IngressLifecycle {

  /** Begins accepting client requests. Called last in startup, immediately before {@code READY}. */
  void startAccepting();

  /**
   * Stops accepting new client requests. Called first in shutdown, before the broker is drained.
   * Implementations should return promptly and must not block on in-flight requests completing.
   */
  void stopAccepting();
}
