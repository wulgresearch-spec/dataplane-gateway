package io.reliabilityai.gateway.dataplane.memory.store.journal;

/**
 * A journal entry could not be decoded.
 *
 * <p>Never recovered from by guessing. A partially-decoded entry would put a fabricated record into
 * the store, and every guarantee above it — integrity, audit, delete proof — assumes a record is
 * exactly what was written.
 *
 * <p>Distinct from {@code MemoryStoreUnavailableException}: unavailability is transient and a
 * caller should retry, whereas corruption is permanent for that entry and retrying achieves
 * nothing. Recovery treats the two very differently, so collapsing them would make one of the two
 * handled wrongly.
 */
final class JournalCorruptException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  JournalCorruptException(final String message) {
    super(message);
  }

  JournalCorruptException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
