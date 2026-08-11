package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;

/**
 * When {@link LocalWal} forces buffered writes to stable storage (Doc 07 EV-D3). Configurable so an
 * operator trades durability for throughput without changing any caller.
 */
public enum WalSyncPolicy {
  /**
   * {@code fsync} after every append/mark — strongest crash durability (RPO=0 to the last returned
   * call), lowest throughput. The default for zero-loss delivery.
   */
  ALWAYS,
  /**
   * No explicit {@code fsync}; rely on the OS page cache to flush. Highest throughput; a power loss
   * can lose the most recent unsynced records. Acceptable only where the delivery class tolerates
   * it.
   */
  NONE
}
