package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.metering.api.DurableUsageWalPort;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;

/**
 * The durable usage journal on the single VPS (Doc 23 §durability, Doc 24). Metering must not
 * acknowledge an attempt as recorded until the fact is durable; this binding delegates that
 * durability to the zero-loss publisher, whose local WAL appends and fsyncs the record
 * <em>before</em> it is sent.
 *
 * <p>Returns {@code false} — never throws — when the append fails, so metering can degrade to its
 * own unrecorded path instead of failing the customer's request over an accounting write.
 */
public final class PublishingUsageJournal implements DurableUsageWalPort {

  private final EventPublisherPort publisher;
  private final String topic;

  /**
   * Creates the journal.
   *
   * @param publisher the durable event publisher (WAL-backed)
   * @param topic the usage-journal topic
   */
  public PublishingUsageJournal(final EventPublisherPort publisher, final String topic) {
    this.publisher = Preconditions.requireNonNull(publisher, "publisher");
    this.topic = Preconditions.requireNonBlank(topic, "topic");
  }

  @Override
  public boolean commit(final UsageFact fact) {
    if (fact == null) {
      return false;
    }
    try {
      publisher.publish(topic, DeliveryClass.ZL, fact);
      return true;
    } catch (final RuntimeException appendFailure) {
      return false; // not durable → metering treats the attempt as unrecorded (fail-closed
      // accounting)
    }
  }
}
