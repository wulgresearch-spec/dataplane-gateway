package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicy;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicyPort;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Supplies the node-wide {@link ReliabilityPolicy} from an immutable, operator-pinned value (Doc
 * 20). The reference is swapped atomically on republish so an in-flight invocation keeps the policy
 * it started with.
 */
public final class StaticReliabilityPolicySource implements ReliabilityPolicyPort {

  private final AtomicReference<ReliabilityPolicy> pinned = new AtomicReference<>();

  /**
   * Creates the source with the startup policy.
   *
   * @param initial the reliability policy pinned at startup
   */
  public StaticReliabilityPolicySource(final ReliabilityPolicy initial) {
    pinned.set(Preconditions.requireNonNull(initial, "initial"));
  }

  @Override
  public Optional<ReliabilityPolicy> current() {
    return Optional.ofNullable(pinned.get());
  }

  /**
   * Atomically replaces the pinned policy.
   *
   * @param policy the newly validated reliability policy
   */
  public void applyPublished(final ReliabilityPolicy policy) {
    pinned.set(Preconditions.requireNonNull(policy, "policy"));
  }
}
