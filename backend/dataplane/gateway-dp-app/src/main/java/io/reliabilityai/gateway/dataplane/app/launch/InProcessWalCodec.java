package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalCodec;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A write-ahead-log codec that preserves payload identity within a single JVM run.
 *
 * <p><b>This is the one deliberately incomplete component in the launcher, and it bounds what the
 * node guarantees.</b> The runtime publishes several unrelated {@link ContentFree} types — usage
 * facts, accounting facts, audit records — and a real node ships a schema-based codec that can
 * reconstruct each of them from bytes alone. This codec instead writes an index into an in-memory
 * table, so a payload survives a WAL round-trip inside one process but <em>cannot</em> be recovered
 * after a restart.
 *
 * <p>The practical consequence: durability of emitted facts across a crash is not delivered by a
 * node wired this way. Entries written by a previous run decode to {@link Unrecoverable} rather
 * than throwing, so a restart over a non-empty log starts cleanly instead of dying on replay —
 * visibly lossy, which is the honest failure mode, rather than silently wrong.
 */
final class InProcessWalCodec implements WalCodec {

  /** Tag marking a payload recorded in this run's table. */
  private static final byte TAG_INDEXED = 1;

  private final List<ContentFree> table = new CopyOnWriteArrayList<>();

  /** Replaces a payload written before this process started. */
  record Unrecoverable(String topic) implements ContentFree {}

  @Override
  public byte[] encode(final ContentFree payload) {
    table.add(payload);
    return ByteBuffer.allocate(5).put(TAG_INDEXED).putInt(table.size() - 1).array();
  }

  @Override
  public ContentFree decode(final String topic, final byte[] bytes) {
    if (bytes == null || bytes.length != 5) {
      return new Unrecoverable(topic);
    }
    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
    final byte tag = buffer.get();
    final int index = buffer.getInt();
    if (tag != TAG_INDEXED || index < 0 || index >= table.size()) {
      return new Unrecoverable(topic);
    }
    return table.get(index);
  }
}
