package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * Outbound seam to the structured-log backend (Doc 27 §4, Doc 14 §7). Owns no store. Verbose logs
 * are best-effort and may be shed under saturation (Doc 27 §18.2).
 */
public interface LogSinkPort {

  /**
   * Records a content-free log record.
   *
   * @param record the log record
   */
  void record(CanonicalLogRecord record);
}
