package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.authn.api.AuthAuditPort;
import io.reliabilityai.gateway.dataplane.authn.api.AuthenticationDecisionRecord;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;

/**
 * Publishes every authentication decision — accepted and rejected alike — to the durable audit
 * stream at {@link DeliveryClass#ZL} (Doc 37 §audit). Rejections matter most: a lost rejection is a
 * lost signal that someone probed the gateway, so the record is WAL-appended before delivery.
 * {@link AuthenticationDecisionRecord} is content-free — it names the principal and outcome, never
 * the presented token.
 */
public final class PublishingAuthAudit implements AuthAuditPort {

  private final EventPublisherPort publisher;
  private final String topic;

  /**
   * Creates the audit sink.
   *
   * @param publisher the durable event publisher
   * @param topic the authentication-audit topic
   */
  public PublishingAuthAudit(final EventPublisherPort publisher, final String topic) {
    this.publisher = Preconditions.requireNonNull(publisher, "publisher");
    this.topic = Preconditions.requireNonBlank(topic, "topic");
  }

  @Override
  public void record(final AuthenticationDecisionRecord decision) {
    Preconditions.requireNonNull(decision, "decision");
    publisher.publish(topic, DeliveryClass.ZL, decision);
  }
}
