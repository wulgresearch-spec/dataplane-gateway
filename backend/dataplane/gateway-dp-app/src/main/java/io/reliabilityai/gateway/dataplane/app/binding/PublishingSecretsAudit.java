package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.secrets.api.MaterializationRecord;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretsAuditPort;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;

/**
 * Publishes every credential-materialization decision to the durable audit stream at {@link
 * DeliveryClass#ZL} (Doc 26 §audit). Credential access is a security-relevant event, so the record
 * must survive a crash: it is WAL-appended before delivery. {@link MaterializationRecord} is
 * content-free by construction — it names the lease and outcome, never the secret.
 */
public final class PublishingSecretsAudit implements SecretsAuditPort {

  private final EventPublisherPort publisher;
  private final String topic;

  /**
   * Creates the audit sink.
   *
   * @param publisher the durable event publisher
   * @param topic the secrets-audit topic
   */
  public PublishingSecretsAudit(final EventPublisherPort publisher, final String topic) {
    this.publisher = Preconditions.requireNonNull(publisher, "publisher");
    this.topic = Preconditions.requireNonBlank(topic, "topic");
  }

  @Override
  public void record(final MaterializationRecord record) {
    Preconditions.requireNonNull(record, "record");
    publisher.publish(topic, DeliveryClass.ZL, record);
  }
}
