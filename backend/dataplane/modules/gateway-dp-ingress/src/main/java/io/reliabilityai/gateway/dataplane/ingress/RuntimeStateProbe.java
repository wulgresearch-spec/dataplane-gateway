package io.reliabilityai.gateway.dataplane.ingress;

/**
 * Reports runtime lifecycle state to the health and readiness endpoints (Doc 29).
 *
 * <p>Liveness and readiness are deliberately separate. {@code /health} answers "is this process
 * alive and what is it doing" and stays 200 even while starting or failed, so an orchestrator can
 * read the state rather than being told only that the socket answered. {@code /ready} answers "may
 * traffic be sent here" and is 503 for anything but READY — a node that is still replaying its WAL
 * must not be put into a load-balancer pool.
 */
public interface RuntimeStateProbe {

  /**
   * The runtime's current lifecycle state name.
   *
   * @return the state name, e.g. {@code READY} or {@code STARTING}
   */
  String state();

  /**
   * Whether the runtime may serve traffic.
   *
   * @return {@code true} only when fully ready
   */
  boolean ready();
}
