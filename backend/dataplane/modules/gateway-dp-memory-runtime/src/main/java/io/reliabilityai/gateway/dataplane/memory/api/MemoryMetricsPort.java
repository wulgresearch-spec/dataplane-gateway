package io.reliabilityai.gateway.dataplane.memory.api;

import java.time.Duration;

/**
 * Where the memory plane's operational signals go.
 *
 * <p>A port, so the runtime emits without knowing where. No new sink is introduced; an adapter
 * feeds the telemetry infrastructure that already exists.
 *
 * <p>Four of these matter more than the rest because each is silent until it is expensive: {@link
 * #refused} dimensioned by stage shows which gate is actually binding; {@link #unenforceable}
 * should read zero and any other value is a broken dependency failing closed; {@link #degraded}
 * shows the vector path is down without anyone noticing the answers got worse; and {@link
 * #isolationViolation} must always be zero, because a non-zero value means an adapter returned a
 * record from outside the narrowed scope.
 */
public interface MemoryMetricsPort {

  /** A metrics port that discards everything. */
  MemoryMetricsPort NOOP = new MemoryMetricsPort() {};

  /**
   * A memory was written.
   *
   * @param type the memory kind
   * @param classification what was established
   * @param sealed whether it was sealed at rest
   * @param took how long the write took end to end
   */
  default void written(
      MemoryType type, DataClassification classification, boolean sealed, Duration took) {}

  /**
   * A query completed.
   *
   * @param mode the mode actually used
   * @param examined how many records the adapters returned before filtering
   * @param returned how many survived filtering and ranking
   * @param took how long the read took end to end
   */
  default void read(RetrievalMode mode, int examined, int returned, Duration took) {}

  /**
   * An operation was refused.
   *
   * @param stage which stage refused
   * @param code the stable refusal code
   */
  default void refused(MemoryStage stage, String code) {}

  /**
   * Governance or policy could not be established, so the operation failed closed.
   *
   * <p>Expected value zero. Anything else is a dependency outage being correctly converted into
   * refusals rather than into an open door — correct, but not something to leave running.
   *
   * @param stage where it happened
   */
  default void unenforceable(MemoryStage stage) {}

  /**
   * A hybrid query fell back to keyword because the vector path was unavailable.
   *
   * @param mode the mode originally requested
   */
  default void degraded(RetrievalMode mode) {}

  /**
   * An adapter returned a record outside the narrowed scope, and the runtime dropped it.
   *
   * <p><b>Must always be zero.</b> A non-zero value is an adapter defect with cross-tenant
   * consequences, caught by the re-verification in AD-026 §6.1 — which is defence in depth working,
   * and an incident regardless.
   *
   * @param adapter an opaque adapter identifier
   */
  default void isolationViolation(String adapter) {}

  /**
   * A record's stored digest did not match its content.
   *
   * @param type the memory kind
   */
  default void integrityFailure(MemoryType type) {}

  /**
   * A memory was destroyed and a proof recorded.
   *
   * @param type the memory kind
   */
  default void deleted(MemoryType type) {}

  /**
   * A lifecycle sweep finished.
   *
   * @param expired how many records were expired
   * @param archived how many were archived
   * @param held how many were skipped because of a legal hold
   */
  default void swept(int expired, int archived, int held) {}

  /**
   * A storage dependency was unreachable.
   *
   * @param operation which operation failed
   */
  default void storeUnavailable(String operation) {}

  /**
   * A policy snapshot was installed.
   *
   * @param scopes how many scopes the snapshot resolves
   */
  default void snapshotInstalled(int scopes) {}
}
