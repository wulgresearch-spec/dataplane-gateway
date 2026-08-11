package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.usage.CostFact;
import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.api.ComputationOutcome;
import io.reliabilityai.gateway.dataplane.cost.api.CostEnginePort;
import io.reliabilityai.gateway.dataplane.cost.api.CostRequest;
import io.reliabilityai.gateway.dataplane.cost.api.CostResult;
import io.reliabilityai.gateway.dataplane.cost.domain.NormalizedUsage;
import io.reliabilityai.gateway.dataplane.cost.domain.Phase;
import io.reliabilityai.gateway.dataplane.cost.domain.UsageConfidence;
import io.reliabilityai.gateway.dataplane.metering.api.CostUsagePort;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import java.util.OptionalLong;

/**
 * The metering → cost seam (Doc 22 §pricing-input, Doc 23 §accounting). Metering owns "what was
 * used"; cost owns "what it is worth". This bridge carries an authoritative {@link UsageFact}
 * across that boundary, prices it, and emits the resulting {@link CostFact} durably at {@link
 * DeliveryClass#ZL} — the billing record is appended to the WAL before it is sent, so a crash
 * replays it instead of losing revenue.
 *
 * <p>It performs no pricing arithmetic of its own: every rate, FX conversion and entitlement
 * decision stays inside the cost engine. It only maps identities and units between the two modules'
 * vocabularies and publishes the priced outcome; an unavailable price is dropped here and surfaced
 * by the engine's own outcome sink rather than being guessed at.
 */
public final class CostEngineUsageBridge implements CostUsagePort {

  private final CostEnginePort costEngine;
  private final EventPublisherPort publisher;
  private final String topic;

  /**
   * Creates the bridge.
   *
   * @param costEngine the cost engine that prices the usage
   * @param publisher the durable event publisher carrying the billing record
   * @param topic the cost-fact topic
   */
  public CostEngineUsageBridge(
      final CostEnginePort costEngine, final EventPublisherPort publisher, final String topic) {
    this.costEngine = Preconditions.requireNonNull(costEngine, "costEngine");
    this.publisher = Preconditions.requireNonNull(publisher, "publisher");
    this.topic = Preconditions.requireNonBlank(topic, "topic");
  }

  @Override
  public void submit(final UsageFact fact) {
    Preconditions.requireNonNull(fact, "fact");
    final ComputationOutcome outcome = costEngine.compute(toRequest(fact), toUsage(fact));
    if (outcome instanceof CostResult priced) {
      publisher.publish(topic, DeliveryClass.ZL, toCostFact(fact, priced));
    }
    // CostUnavailable: the engine has already reported the reason to its own outcome sink.
    // Publishing
    // a placeholder price would be worse than publishing nothing — accounting must never invent
    // money.
  }

  private static CostRequest toRequest(final UsageFact fact) {
    return new CostRequest(
        fact.tenantScope(),
        fact.canonicalModelId(),
        fact.region(),
        // UsageFact carries no correlation id; the idempotency key is the stable per-request
        // identity
        // that accounting already joins on, so it is the correct deterministic substitute here.
        new CorrelationId(fact.idempotencyKey().value()),
        fact.idempotencyKey(),
        fact.attemptId(),
        Phase.ACTUAL,
        fact.units().prompt(),
        OptionalLong.empty(),
        fact.delivered(),
        fact.usageClass() == UsageClass.ESTIMATED);
  }

  private static NormalizedUsage toUsage(final UsageFact fact) {
    return new NormalizedUsage(
        fact.units().prompt(),
        fact.units().completion() + fact.units().reasoning() + fact.units().toolTokens(),
        fact.units().cached(),
        1L,
        fact.usageClass() == UsageClass.AUTHORITATIVE
            ? UsageConfidence.AUTHORITATIVE
            : UsageConfidence.ESTIMATED);
  }

  private static CostFact toCostFact(final UsageFact fact, final CostResult priced) {
    return new CostFact(
        new ExecutionIdentity(
            fact.idempotencyKey(),
            fact.attemptId(),
            new CorrelationId(fact.idempotencyKey().value())),
        fact.tenantScope(),
        fact.canonicalModelId(),
        priced.amount().amountMicros(),
        priced.amount().currency(),
        fact.timestamp());
  }
}
