package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.WalPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Single-VPS realization of {@link WalPort} (Doc 07 EV-D3): a node-local, append-only, segmented
 * write-ahead log giving zero-loss (RPO=0) delivery without any external durable store. A zero-loss
 * record is {@link #append appended} (and {@code fsync}'d, per {@link WalConfig}) <b>before</b> the
 * broker send and {@link #markSent marked} only on confirmed delivery; on crash the un-marked
 * records are {@link #replayPending replayed} in append order, so a genuinely-new event is never
 * dropped as a consumer-side duplicate.
 *
 * <p><b>On-disk format.</b> Segment files {@code wal-000000.log, wal-000001.log, …}; each record is
 * a self-describing frame {@code [u8 type | u32 bodyLen | body | u32 CRC32]} (big-endian), the CRC
 * over the type+length+body. Types: {@code APPEND} (id, topic, delivery class, codec-encoded
 * payload), {@code SENT} (id), {@code CHECKPOINT} (node → high-water mark, written before
 * compaction so a deleted segment can never regress the id counter). <b>Crash recovery:</b> a torn
 * tail frame (partial write / bad CRC) truncates the active segment at the last good frame — never
 * a partial record surfaced. <b>Rotation:</b> the active segment rolls at {@link
 * WalConfig#maxSegmentBytes}. <b>Compaction hook:</b> {@link #compact()} deletes fully-acknowledged
 * older segments after checkpointing. Deterministic (no clock/RNG, R-063); ordering is segment
 * order then in-segment offset.
 *
 * <p>Thread- and virtual-thread-safe: all mutations hold a {@link ReentrantLock} (never a monitor
 * across I/O, so no VT pinning, R-049). Idempotent per {@code eventId}.
 *
 * <p><b>AWS migration:</b> replace with a Kafka transactional-outbox / durable-WAL adapter behind
 * the same {@link WalPort}; the publisher and callers are unchanged.
 */
public final class LocalWal implements WalPort, AutoCloseable {

  private static final byte TYPE_APPEND = 1;
  private static final byte TYPE_SENT = 2;
  private static final byte TYPE_CHECKPOINT = 3;
  private static final int HEADER_BYTES = 1 + 4; // type + bodyLen
  private static final int CRC_BYTES = 4;
  private static final String SEGMENT_PREFIX = "wal-";
  private static final String SEGMENT_SUFFIX = ".log";

  private final Path directory;
  private final WalConfig config;
  private final WalCodec codec;
  private final ReentrantLock lock = new ReentrantLock();

  // Pending = appended but not yet marked sent, in append order (for replay + compaction). Guarded.
  private final Set<String> pendingIds = new LinkedHashSet<>();
  // nodeId -> highest sequence ever appended (guarded); survives compaction via checkpoints.
  private final Map<String, Long> nodeHighWater = new LinkedHashMap<>();

  private int activeIndex;
  private FileChannel activeChannel;
  private long activeSize;

  /**
   * Opens (or recovers) the WAL rooted at {@code directory}, creating it if absent.
   *
   * @param directory the node-local WAL directory
   * @param config the rotation + fsync configuration
   * @param codec the payload byte-codec (adapter-internal; see {@link WalCodec})
   */
  public LocalWal(final Path directory, final WalConfig config, final WalCodec codec) {
    this.directory = Preconditions.requireNonNull(directory, "directory");
    this.config = Preconditions.requireNonNull(config, "config");
    this.codec = Preconditions.requireNonNull(codec, "codec");
    lock.lock();
    try {
      Files.createDirectories(directory);
      recover();
    } catch (final IOException e) {
      throw new UncheckedIOException("WAL open/recover failed", e);
    } finally {
      lock.unlock();
    }
  }

  // --- WalPort ---

  @Override
  public void append(final BrokerRecord record) {
    Preconditions.requireNonNull(record, "record");
    final byte[] payload =
        codec.encode(record.payload()); // fail-closed if the payload cannot be encoded
    final byte[] body =
        encodeAppendBody(record.eventId(), record.topic(), record.deliveryClass(), payload);
    lock.lock();
    try {
      writeFrame(TYPE_APPEND, body);
      pendingIds.add(record.eventId());
      trackHighWater(record.eventId());
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void markSent(final String eventId) {
    Preconditions.requireNonBlank(eventId, "eventId");
    lock.lock();
    try {
      writeFrame(TYPE_SENT, encodeSentBody(eventId));
      pendingIds.remove(eventId);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public long highWaterMark(final String nodeId) {
    Preconditions.requireNonBlank(nodeId, "nodeId");
    lock.lock();
    try {
      return nodeHighWater.getOrDefault(nodeId, 0L);
    } finally {
      lock.unlock();
    }
  }

  // --- adapter capabilities (beyond the frozen port; used by the composition wiring) ---

  /**
   * Replays every appended-but-unsent record in append order (crash recovery, Doc 07 EV-D3).
   * Records are re-materialized from disk via the codec so no payload is held in memory during
   * steady state.
   *
   * @param consumer the replay sink (typically re-drives the broker send)
   */
  public void replayPending(final Consumer<BrokerRecord> consumer) {
    Preconditions.requireNonNull(consumer, "consumer");
    lock.lock();
    try {
      if (pendingIds.isEmpty()) {
        return;
      }
      final List<BrokerRecord> replay = new ArrayList<>();
      forEachAppendOnDisk(
          (eventId, topic, deliveryClass, payloadBytes) -> {
            if (pendingIds.contains(eventId)) {
              replay.add(
                  new BrokerRecord(
                      eventId, topic, deliveryClass, codec.decode(topic, payloadBytes)));
            }
          });
      for (final BrokerRecord record : replay) {
        consumer.accept(record);
      }
    } finally {
      lock.unlock();
    }
  }

  /** The number of appended-but-unsent records currently held for replay. */
  public int pendingCount() {
    lock.lock();
    try {
      return pendingIds.size();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Compaction hook: checkpoints the per-node high-water mark, then deletes every non-active
   * segment whose records are all acknowledged (no pending record references them). Safe to call
   * from a cron/ scheduler; never touches pending records or the id-uniqueness guarantee.
   *
   * @return the number of segment files deleted
   */
  public int compact() {
    lock.lock();
    try {
      final List<Path> segments = listSegments();
      if (segments.size() <= 1) {
        return 0; // only the active segment: nothing older to reclaim
      }
      final Path active = segments.get(segments.size() - 1);
      final List<Path> deletable = new ArrayList<>();
      for (final Path segment : segments) {
        if (segment.equals(active)) {
          continue;
        }
        if (segmentIsFullyAcknowledged(segment)) {
          deletable.add(segment);
        }
      }
      if (deletable.isEmpty()) {
        return 0;
      }
      // Preserve the id counter before deleting any segment that may hold the max-seq record.
      for (final Map.Entry<String, Long> node : nodeHighWater.entrySet()) {
        writeFrame(TYPE_CHECKPOINT, encodeCheckpointBody(node.getKey(), node.getValue()));
      }
      int deleted = 0;
      for (final Path segment : deletable) {
        try {
          Files.delete(segment);
          deleted++;
        } catch (final IOException e) {
          throw new UncheckedIOException("WAL compaction delete failed", e);
        }
      }
      return deleted;
    } finally {
      lock.unlock();
    }
  }

  /** The current number of on-disk segment files (for operational visibility / tests). */
  public int segmentCount() {
    lock.lock();
    try {
      return listSegments().size();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      if (activeChannel != null) {
        try {
          activeChannel.close();
        } catch (final IOException e) {
          throw new UncheckedIOException("WAL close failed", e);
        }
      }
    } finally {
      lock.unlock();
    }
  }

  // --- recovery ---

  private void recover() throws IOException {
    final List<Path> segments = listSegments();
    if (segments.isEmpty()) {
      openNewActiveSegment(0);
      return;
    }
    for (int i = 0; i < segments.size(); i++) {
      final Path segment = segments.get(i);
      final byte[] data = Files.readAllBytes(segment);
      final long validLength = scan(data, this::applyRecovered);
      if (validLength < data.length && i == segments.size() - 1) {
        // Torn tail on the active segment (crash mid-write): truncate to the last good frame.
        try (FileChannel channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
          channel.truncate(validLength);
        }
      }
    }
    // Re-open the highest-indexed segment as the active one, positioned at its (recovered) end.
    activeIndex = segmentIndex(segments.get(segments.size() - 1));
    final Path activePath = segmentPath(activeIndex);
    activeChannel =
        FileChannel.open(activePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    activeSize = activeChannel.size();
    activeChannel.position(activeSize);
  }

  private void applyRecovered(final byte type, final byte[] body) {
    switch (type) {
      case TYPE_APPEND -> {
        final String eventId = decodeAppendEventId(body);
        pendingIds.add(eventId);
        trackHighWater(eventId);
      }
      case TYPE_SENT -> pendingIds.remove(decodeString(body, 0));
      case TYPE_CHECKPOINT -> {
        final CheckpointBody checkpoint = decodeCheckpoint(body);
        nodeHighWater.merge(checkpoint.nodeId(), checkpoint.highWaterMark(), Math::max);
      }
      default -> {
        // Unknown record type from a corrupt/foreign frame — ignore (fail closed by not applying).
      }
    }
  }

  // --- framing ---

  private void writeFrame(final byte type, final byte[] body) {
    final byte[] frame = frameBytes(type, body);
    try {
      rotateIfNeeded(frame.length);
      activeChannel.write(ByteBuffer.wrap(frame));
      if (config.syncPolicy() == WalSyncPolicy.ALWAYS) {
        activeChannel.force(false); // fsync data; metadata durability is not required per record
      }
      activeSize += frame.length;
    } catch (final IOException e) {
      throw new UncheckedIOException("WAL write failed", e);
    }
  }

  private void rotateIfNeeded(final int frameLength) throws IOException {
    if (activeSize > 0 && activeSize + frameLength > config.maxSegmentBytes()) {
      activeChannel.force(true);
      activeChannel.close();
      openNewActiveSegment(activeIndex + 1);
    }
  }

  private void openNewActiveSegment(final int index) throws IOException {
    activeIndex = index;
    activeChannel =
        FileChannel.open(
            segmentPath(index),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND);
    activeSize = activeChannel.size();
  }

  private static byte[] frameBytes(final byte type, final byte[] body) {
    final ByteBuffer buffer = ByteBuffer.allocate(HEADER_BYTES + body.length + CRC_BYTES);
    buffer.put(type);
    buffer.putInt(body.length);
    buffer.put(body);
    final CRC32 crc = new CRC32();
    crc.update(buffer.array(), 0, HEADER_BYTES + body.length);
    buffer.putInt((int) crc.getValue());
    return buffer.array();
  }

  /**
   * Scans frames from a segment, invoking the handler per good frame; returns the valid prefix
   * length.
   */
  private static long scan(final byte[] data, final FrameHandler handler) {
    int offset = 0;
    while (offset + HEADER_BYTES + CRC_BYTES <= data.length) {
      final byte type = data[offset];
      final int bodyLen = readInt(data, offset + 1);
      final long frameLen = (long) HEADER_BYTES + bodyLen + CRC_BYTES;
      if (bodyLen < 0 || offset + frameLen > data.length) {
        break; // torn / truncated tail
      }
      final CRC32 crc = new CRC32();
      crc.update(data, offset, HEADER_BYTES + bodyLen);
      final int storedCrc = readInt(data, offset + HEADER_BYTES + bodyLen);
      if ((int) crc.getValue() != storedCrc) {
        break; // corrupt / torn frame — stop at the last good one
      }
      final byte[] body = new byte[bodyLen];
      System.arraycopy(data, offset + HEADER_BYTES, body, 0, bodyLen);
      handler.handle(type, body);
      offset += (int) frameLen;
    }
    return offset;
  }

  @FunctionalInterface
  private interface FrameHandler {
    void handle(byte type, byte[] body);
  }

  @FunctionalInterface
  private interface AppendHandler {
    void handle(String eventId, String topic, DeliveryClass deliveryClass, byte[] payload);
  }

  private void forEachAppendOnDisk(final AppendHandler handler) {
    for (final Path segment : listSegments()) {
      final byte[] data;
      try {
        data = Files.readAllBytes(segment);
      } catch (final IOException e) {
        throw new UncheckedIOException("WAL replay read failed", e);
      }
      scan(
          data,
          (type, body) -> {
            if (type == TYPE_APPEND) {
              final AppendBody append = decodeAppend(body);
              handler.handle(
                  append.eventId(), append.topic(), append.deliveryClass(), append.payload());
            }
          });
    }
  }

  private boolean segmentIsFullyAcknowledged(final Path segment) {
    final byte[] data;
    try {
      data = Files.readAllBytes(segment);
    } catch (final IOException e) {
      throw new UncheckedIOException("WAL compaction read failed", e);
    }
    final boolean[] hasPending = {false};
    scan(
        data,
        (type, body) -> {
          if (type == TYPE_APPEND && pendingIds.contains(decodeAppendEventId(body))) {
            hasPending[0] = true;
          }
        });
    return !hasPending[0];
  }

  // --- body codecs (big-endian, length-prefixed) ---

  private static byte[] encodeAppendBody(
      final String eventId,
      final String topic,
      final DeliveryClass deliveryClass,
      final byte[] payload) {
    final byte[] id = eventId.getBytes(StandardCharsets.UTF_8);
    final byte[] top = topic.getBytes(StandardCharsets.UTF_8);
    final ByteBuffer buffer =
        ByteBuffer.allocate(2 + id.length + 2 + top.length + 1 + 4 + payload.length);
    putString(buffer, id);
    putString(buffer, top);
    buffer.put((byte) deliveryClass.ordinal());
    buffer.putInt(payload.length);
    buffer.put(payload);
    return buffer.array();
  }

  private static AppendBody decodeAppend(final byte[] body) {
    int offset = 0;
    final int idLen = readShort(body, offset);
    final String eventId = new String(body, offset + 2, idLen, StandardCharsets.UTF_8);
    offset += 2 + idLen;
    final int topLen = readShort(body, offset);
    final String topic = new String(body, offset + 2, topLen, StandardCharsets.UTF_8);
    offset += 2 + topLen;
    final DeliveryClass deliveryClass = DeliveryClass.values()[body[offset] & 0xFF];
    offset += 1;
    final int payloadLen = readInt(body, offset);
    offset += 4;
    final byte[] payload = new byte[payloadLen];
    System.arraycopy(body, offset, payload, 0, payloadLen);
    return new AppendBody(eventId, topic, deliveryClass, payload);
  }

  private static String decodeAppendEventId(final byte[] body) {
    return decodeString(body, 0);
  }

  private static byte[] encodeSentBody(final String eventId) {
    final byte[] id = eventId.getBytes(StandardCharsets.UTF_8);
    final ByteBuffer buffer = ByteBuffer.allocate(2 + id.length);
    putString(buffer, id);
    return buffer.array();
  }

  private static byte[] encodeCheckpointBody(final String nodeId, final long highWaterMark) {
    final byte[] node = nodeId.getBytes(StandardCharsets.UTF_8);
    final ByteBuffer buffer = ByteBuffer.allocate(2 + node.length + 8);
    putString(buffer, node);
    buffer.putLong(highWaterMark);
    return buffer.array();
  }

  private static CheckpointBody decodeCheckpoint(final byte[] body) {
    final int nodeLen = readShort(body, 0);
    final String nodeId = new String(body, 2, nodeLen, StandardCharsets.UTF_8);
    final long highWaterMark = readLong(body, 2 + nodeLen);
    return new CheckpointBody(nodeId, highWaterMark);
  }

  private record AppendBody(
      String eventId, String topic, DeliveryClass deliveryClass, byte[] payload) {}

  private record CheckpointBody(String nodeId, long highWaterMark) {}

  private static void putString(final ByteBuffer buffer, final byte[] utf8) {
    if (utf8.length > 0xFFFF) {
      throw new IllegalArgumentException("WAL string field exceeds 65535 bytes");
    }
    buffer.putShort((short) utf8.length);
    buffer.put(utf8);
  }

  private static String decodeString(final byte[] body, final int offset) {
    final int len = readShort(body, offset);
    return new String(body, offset + 2, len, StandardCharsets.UTF_8);
  }

  private void trackHighWater(final String eventId) {
    // eventId = nodeId + "-" + <ZL|RL|BE> + "-" + <sequence> (ResilientEventPublisher). Parse the
    // node
    // and sequence back out so a restart never regresses the monotonic id counter (Doc 07 §6).
    final int lastDash = eventId.lastIndexOf('-');
    if (lastDash <= 0) {
      return;
    }
    final long sequence;
    try {
      sequence = Long.parseLong(eventId.substring(lastDash + 1));
    } catch (final NumberFormatException notOurFormat) {
      return;
    }
    final int classDash = eventId.lastIndexOf('-', lastDash - 1);
    if (classDash <= 0) {
      return;
    }
    final String nodeId = eventId.substring(0, classDash);
    nodeHighWater.merge(nodeId, sequence, Math::max);
  }

  // --- segment paths ---

  private List<Path> listSegments() {
    final List<Path> segments = new ArrayList<>();
    try (var stream = Files.list(directory)) {
      stream
          .filter(
              p -> {
                final String name = p.getFileName().toString();
                return name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX);
              })
          .sorted()
          .forEach(segments::add);
    } catch (final IOException e) {
      throw new UncheckedIOException("WAL segment listing failed", e);
    }
    return segments;
  }

  private Path segmentPath(final int index) {
    // Locale.ROOT, not the default locale. %d renders through the locale's zero digit, so under a
    // locale whose default numbering system is not latn (ar-SA and fa-IR among them) this produced
    // segment names in Arabic-Indic digits. Nothing here notices immediately: Integer.parseInt
    // accepts those digits, so a node that keeps one locale for ever works. The damage appears when
    // the locale changes between runs — a JVM upgrade, a different base image, a stray
    // -Duser.language — because listSegments() orders segments by filename, and non-ASCII digits
    // sort after ASCII ones. Recovery would then take the wrong file as the highest-indexed
    // segment, reopen it as active, and append over a segment that is not the newest. That is
    // silent data loss in the write-ahead log, which is the one component that exists to prevent
    // exactly that.
    return directory.resolve(
        String.format(Locale.ROOT, "%s%06d%s", SEGMENT_PREFIX, index, SEGMENT_SUFFIX));
  }

  private static int segmentIndex(final Path segment) {
    final String name = segment.getFileName().toString();
    return Integer.parseInt(
        name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length()));
  }

  // --- big-endian readers ---

  private static int readShort(final byte[] b, final int off) {
    return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
  }

  private static int readInt(final byte[] b, final int off) {
    return ((b[off] & 0xFF) << 24)
        | ((b[off + 1] & 0xFF) << 16)
        | ((b[off + 2] & 0xFF) << 8)
        | (b[off + 3] & 0xFF);
  }

  private static long readLong(final byte[] b, final int off) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (b[off + i] & 0xFF);
    }
    return value;
  }
}
