package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * Where run facts are mirrored for audit and live observation (AD-025 §47, OBC-1, OBC-2).
 *
 * <p><b>The history is authoritative; this is a view.</b> Emission here is best-effort by contract
 * — a dropped event, a slow consumer or an unavailable sink must never affect a run's correctness.
 * That rule is what stops an observability outage becoming a run outage, which is the mistake that
 * turns a dashboard problem into an incident.
 *
 * <p>The Agent Runtime introduces no new audit sink and no new retention class. The adapter feeds
 * the existing one, under the existing redaction and retention rules.
 */
public interface RunAuditPort {

  /** A port that discards everything. */
  RunAuditPort NOOP = (runId, offset, event) -> {};

  /**
   * Mirrors one durably-recorded event.
   *
   * <p>Called only <em>after</em> the event is durable, so a consumer can never see a fact that a
   * crash then unmakes.
   *
   * @param runId the run
   * @param offset the event's offset in the history, which doubles as the stream position a
   *     reconnecting consumer resumes from
   * @param event the recorded event
   */
  void recorded(RunId runId, long offset, RunEvent event);
}
