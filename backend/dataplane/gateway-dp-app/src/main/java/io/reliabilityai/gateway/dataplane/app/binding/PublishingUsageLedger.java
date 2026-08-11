package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.metering.api.LedgerSinkPort;
import io.reliabilityai.gateway.dataplane.metering.domain.RequestUsageManifest;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Emits accounting output to the durable ledger (Doc 23). Per-attempt {@link UsageFact}s are
 * content-free and go out at {@link DeliveryClass#ZL}.
 *
 * <p>{@link RequestUsageManifest} is <b>not</b> content-free — it carries no tenant or execution
 * identity and is not publishable as a canonical fact — so the manifest is counted and its last
 * value retained for operational inspection rather than published. The per-attempt facts already
 * carry the full billable record; the manifest is a per-request completeness summary derived from
 * them.
 */
public final class PublishingUsageLedger implements LedgerSinkPort {

  private final EventPublisherPort publisher;
  private final String topic;
  private final AtomicLong manifests = new AtomicLong();
  private final AtomicLong incompleteManifests = new AtomicLong();
  private final AtomicReference<RequestUsageManifest> lastManifest = new AtomicReference<>();

  /**
   * Creates the ledger sink.
   *
   * @param publisher the durable event publisher
   * @param topic the usage-ledger topic
   */
  public PublishingUsageLedger(final EventPublisherPort publisher, final String topic) {
    this.publisher = Preconditions.requireNonNull(publisher, "publisher");
    this.topic = Preconditions.requireNonBlank(topic, "topic");
  }

  @Override
  public void emitFact(final UsageFact fact) {
    Preconditions.requireNonNull(fact, "fact");
    publisher.publish(topic, DeliveryClass.ZL, fact);
  }

  @Override
  public void emitManifest(final RequestUsageManifest manifest) {
    Preconditions.requireNonNull(manifest, "manifest");
    manifests.incrementAndGet();
    if (!manifest.complete()) {
      incompleteManifests.incrementAndGet();
    }
    lastManifest.set(manifest);
  }

  /**
   * The number of request manifests finalized since startup.
   *
   * @return the manifest count
   */
  public long manifestCount() {
    return manifests.get();
  }

  /**
   * The number of finalized manifests that were incomplete (an attempt went unrecorded).
   *
   * @return the incomplete-manifest count
   */
  public long incompleteManifestCount() {
    return incompleteManifests.get();
  }

  /**
   * The most recently finalized manifest, or {@code null} if none has been finalized.
   *
   * @return the last manifest
   */
  public RequestUsageManifest lastManifest() {
    return lastManifest.get();
  }
}
