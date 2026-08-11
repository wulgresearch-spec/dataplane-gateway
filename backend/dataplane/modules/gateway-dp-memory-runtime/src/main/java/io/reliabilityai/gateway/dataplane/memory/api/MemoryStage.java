package io.reliabilityai.gateway.dataplane.memory.api;

/**
 * The stages of the write and read pipelines, in frozen order (AD-026 §5, §6).
 *
 * <p>The order is the order of these constants, and both pipelines are written as a straight
 * sequence against them. There is no stage registry and no dynamic composition — a reviewer reads
 * the pipeline top to bottom and sees the whole contract, which is the same discipline the request
 * pipeline uses.
 */
public enum MemoryStage {

  /** Shape checks: scope complete, type valid, content bounded, query well-formed. */
  ADMIT(true, true),

  /** The governance engine decides whether this principal may do this at all. */
  GOVERNANCE(true, true),

  /** Memory policy shapes an admitted write: TTL, PII, residency, sealing, versioning. */
  POLICY(true, false),

  /** The query is intersected with the caller's authorized scope before any adapter sees it. */
  NARROW(false, true),

  /** The adapters are asked. */
  RETRIEVAL(false, true),

  /** The record is handed to the store, then to the index. */
  STORAGE(true, false),

  /** Expired, archived and re-classified records are dropped from what the adapter returned. */
  POLICY_FILTER(false, true),

  /** The six signals are combined into an order. */
  RANKING(false, true),

  /** The operation is recorded. Always, including for reads that matched nothing. */
  AUDIT(true, true);

  private final boolean onWrite;
  private final boolean onRead;

  MemoryStage(final boolean onWrite, final boolean onRead) {
    this.onWrite = onWrite;
    this.onRead = onRead;
  }

  /**
   * Reports whether this stage runs on the write path.
   *
   * @return true when writes pass through it
   */
  public boolean onWrite() {
    return onWrite;
  }

  /**
   * Reports whether this stage runs on the read path.
   *
   * @return true when reads pass through it
   */
  public boolean onRead() {
    return onRead;
  }
}
