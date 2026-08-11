package io.reliabilityai.gateway.dataplane.reliability.api;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import java.util.List;

/**
 * The terminal, immutable reliability outcome (Doc 20 §6, RE-INV). Sealed and fail-closed: either a
 * {@link Succeeded} winning attempt (a single successful provider outcome — never a duplicated
 * execution) or a {@link Surfaced} failure naming the typed reason. Both carry the deterministic
 * execution history (Doc 20 RE-D9). A successful execution is never duplicated (RE-INV).
 */
public sealed interface InvocationResult
    permits InvocationResult.Succeeded, InvocationResult.Surfaced {

  /**
   * The deterministic per-attempt history.
   *
   * @return the ordered, immutable attempt records
   */
  List<AttemptRecord> history();

  /**
   * A successful invocation (the single winning attempt).
   *
   * @param outcome the winning provider outcome (unary or streaming, handed onward)
   * @param winner the winning route target
   * @param history the deterministic attempt history
   */
  record Succeeded(
      ProviderInvocationResult outcome, RouteTarget winner, List<AttemptRecord> history)
      implements InvocationResult {
    /** Compact constructor validating fields and copying the history. */
    public Succeeded {
      Preconditions.requireNonNull(outcome, "outcome");
      Preconditions.requireNonNull(winner, "winner");
      history = history == null ? List.of() : List.copyOf(history);
    }
  }

  /**
   * A fail-closed surfaced failure (RE-INV).
   *
   * @param reason the typed failure reason
   * @param lastError the last neutral provider error observed, or {@code null}
   * @param history the deterministic attempt history
   */
  record Surfaced(
      ReliabilityFailureReason reason, CanonicalError lastError, List<AttemptRecord> history)
      implements InvocationResult {
    /** Compact constructor validating fields and copying the history. */
    public Surfaced {
      Preconditions.requireNonNull(reason, "reason");
      history = history == null ? List.of() : List.copyOf(history);
    }
  }
}
