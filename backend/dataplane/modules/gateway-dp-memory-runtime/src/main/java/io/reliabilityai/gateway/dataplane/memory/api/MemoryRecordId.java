package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Identifies one memory record.
 *
 * <p>Opaque to the runtime and to every adapter. The runtime never parses it, never derives meaning
 * from it and never orders by it — except as a stable tie-break in ranking, where any total order
 * will do and only stability matters.
 *
 * @param value the opaque identifier, never blank
 */
public record MemoryRecordId(String value) {

  /**
   * Validates the identifier.
   *
   * @param value the opaque identifier
   */
  public MemoryRecordId {
    Preconditions.requireNonBlank(value, "memoryRecordId");
  }

  /**
   * Creates an identifier.
   *
   * @param value the opaque identifier
   * @return the identifier
   */
  public static MemoryRecordId of(final String value) {
    return new MemoryRecordId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
