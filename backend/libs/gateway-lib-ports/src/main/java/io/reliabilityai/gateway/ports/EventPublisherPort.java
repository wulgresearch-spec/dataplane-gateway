package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.common.ContentFree;

/**
 * The outbound event publication port (Doc 07). Publishes content-free events on the frozen event
 * fabric with the standard envelope (Doc 07 §6). Zero-loss (ZL) events use the durable emit WAL +
 * transactional outbox (Doc 07 EV-D3 / Doc 08 DA-D1) — implementations provide effectively-once
 * delivery; the port itself never mutates runtime decisions.
 */
public interface EventPublisherPort {

  /**
   * Publishes a content-free event payload under the given topic with the standard envelope
   * (correlation + causation ids; Doc 07 §6). ZL topics guarantee RPO=0 (Doc 07 ZL contract).
   *
   * @param topic the frozen topic name (Doc 06 §23 registry)
   * @param deliveryClass the delivery class (ZL / RL / BE, Doc 07 §Axis-3)
   * @param payload the content-free event payload
   * @param <T> the content-free payload type
   */
  <T extends ContentFree> void publish(String topic, DeliveryClass deliveryClass, T payload);

  /** Event delivery class (Doc 07 §Axis-3 / §11). */
  enum DeliveryClass {
    /** Zero-loss: durable, idempotent, RPO=0 (audit/accounting/decision/security). */
    ZL,
    /** Reliable: at-least-once, graceful degradation. */
    RL,
    /** Best-effort: high-volume/sampled; never for decision/accounting/audit. */
    BE
  }
}
