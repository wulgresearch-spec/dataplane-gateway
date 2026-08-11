package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * Turns events into durable bytes and back (AD-025 HSC).
 *
 * <p><b>Explicit, never reflective.</b> The codebase forbids reflection, and a durable format is
 * the worst place to break that rule anyway: a reflective serializer makes every field rename a
 * silent data-migration, and makes the on-disk grammar a consequence of the class shape rather than
 * a decision anyone reviewed.
 *
 * <p><b>Round-trip fidelity is a correctness requirement, not a nicety.</b> Replay reconstructs a
 * run by folding its recorded events; if serialization loses or reorders a field, the fold produces
 * a different answer than it did before the crash, and the run diverges. The implementation is
 * therefore required to satisfy {@code deserialize(serialize(e)).equals(e)} for every event, which
 * is a property the test suite asserts rather than assumes.
 */
public interface RunSerializer {

  /**
   * Returns the format version this serializer writes.
   *
   * @return the journal format version, matching {@link RunVersion#CURRENT_JOURNAL_FORMAT} for the
   *     current build
   */
  int formatVersion();

  /**
   * Encodes one event.
   *
   * @param event the event to encode
   * @return a single-line, self-delimiting encoding containing no newline
   */
  String serialize(RunEvent event);

  /**
   * Decodes one event.
   *
   * @param encoded an encoding previously produced by {@link #serialize}
   * @param runId the run the event belongs to, needed to rebuild step identities that are stored
   *     relative to their run rather than repeated in full
   * @return the decoded event
   * @throws RunSerializationException when the encoding is malformed or its tag is unknown; never a
   *     partially-populated event, because a half-decoded fact is worse than an absent one
   */
  RunEvent deserialize(String encoded, RunId runId);
}
