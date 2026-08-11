package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * Outbound seam to the event backbone for Plane-B analytics (Doc 27 §4, Doc 07, Doc 14 §5.1). Emits
 * <b>content-free observations only</b> (Doc 27 §EO) — never a business event; owns no topic.
 */
public interface EventFabricPort {

  /**
   * Publishes a content-free operational observation.
   *
   * @param observation the observation
   */
  void publish(CanonicalTelemetryObservation observation);
}
