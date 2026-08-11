package io.reliabilityai.gateway.dataplane.memory.store.journal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * A segmented, append-only log with crash-safe recovery.
 *
 * <p>Every entry is one line, framed as {@code <crc32hex>:<payload>\n}. Two independent defects are
 * caught by that framing, and they need catching separately:
 *
 * <ul>
 *   <li><b>A torn tail</b> — power lost part-way through a write leaves a line with no terminator.
 *       Detected by reaching end-of-file without a newline.
 *   <li><b>A corrupted line</b> — a partially written sector can happen to contain a newline, so
 *       the line looks complete and is not. Detected by the checksum.
 * </ul>
 *
 * <p>On open the log is read until the first entry that fails either check, and the file is
 * truncated to the last good boundary. <b>A partial entry is discarded, never repaired.</b> A
 * half-parsed record would be a fabricated fact, and every guarantee above this layer assumes a
 * record is exactly what was written.
 *
 * <p><b>Durability.</b> Each append is followed by {@code force(true)} before the call returns.
 * Without it, an acknowledged write can be lost in the page cache when the machine loses power, and
 * the caller would have proceeded believing the record was durable — which is exactly the promise
 * AD-026 A1 makes.
 *
 * <p>Not thread-safe on its own. The owning partition serialises appends; readers work from the
 * in-memory index rather than from this class.
 */
final class SegmentLog implements AutoCloseable {

  private static final char CHECKSUM_SEPARATOR = ':';
  private static final String SEGMENT_PREFIX = "seg-";
  private static final String SEGMENT_SUFFIX = ".log";
  private static final String HEADER_PREFIX = "#memstore=";

  private final Path directory;
  private final long maxSegmentBytes;
  private final boolean syncOnAppend;

  /**
   * How many times this log has forced data to disk.
   *
   * <p>Exists because a missing {@code fsync} is otherwise undetectable from inside the process:
   * the page cache serves the data whether or not it reached the platter, so a test that abandons
   * the process and reopens the store passes either way. Counting the calls does not prove the
   * operating system honoured them — nothing short of cutting power does — but it does prove the
   * code path runs, which turns "durability is implemented" from an assertion into something a test
   * can fail on.
   */
  private final java.util.concurrent.atomic.AtomicLong syncs =
      new java.util.concurrent.atomic.AtomicLong();

  private Path activeSegment;
  private FileChannel channel;
  private long activeBytes;
  private int segmentNumber;

  /**
   * Opens a log, recovering whatever is already on disk.
   *
   * @param directory where segments live; created if absent
   * @param maxSegmentBytes the size at which a new segment is started
   * @param syncOnAppend whether to fsync before acknowledging each append
   * @param onEntry called for every recovered entry, in write order
   */
  SegmentLog(
      final Path directory,
      final long maxSegmentBytes,
      final boolean syncOnAppend,
      final Consumer<JournalCodec.Entry> onEntry) {

    this.directory = Preconditions.requireNonNull(directory, "directory");
    this.syncOnAppend = syncOnAppend;
    if (maxSegmentBytes < 1024L) {
      throw new IllegalArgumentException("maxSegmentBytes must be at least 1024");
    }
    this.maxSegmentBytes = maxSegmentBytes;

    try {
      Files.createDirectories(directory);
      recover(onEntry);
      openActive();
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("cannot open journal at " + directory, failure);
    }
  }

  /**
   * Appends one entry, durably.
   *
   * @param entry the entry to append
   * @throws MemoryStoreUnavailableException when the write or the fsync fails, so a caller never
   *     believes something is durable that is not
   */
  void append(final JournalCodec.Entry entry) {
    Preconditions.requireNonNull(entry, "entry");
    final byte[] framed = frame(JournalCodec.encode(entry));
    try {
      if (activeBytes + framed.length > maxSegmentBytes) {
        rotate();
      }
      channel.write(ByteBuffer.wrap(framed));
      if (syncOnAppend) {
        // Acknowledge only what the disk has. Acknowledging a page-cache write makes AD-026 A1 a
        // lie.
        channel.force(true);
        syncs.incrementAndGet();
      }
      activeBytes += framed.length;
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("journal append failed", failure);
    }
  }

  /**
   * Forces anything buffered to disk.
   *
   * <p>Only meaningful when {@code syncOnAppend} is false — the deliberately relaxed mode used for
   * access-counter updates, where losing the last few is acceptable and the port says so.
   */
  void flush() {
    try {
      channel.force(true);
      syncs.incrementAndGet();
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("journal flush failed", failure);
    }
  }

  /**
   * Replaces every segment with one containing exactly the given entries.
   *
   * <p>Compaction. An append-only log grows without bound: a record written once and read a
   * thousand times leaves a thousand touch entries, and a deleted record leaves both its writes and
   * its tombstone forever. Compaction rewrites only what is live.
   *
   * <p><b>Written to a temporary segment and moved into place atomically.</b> A compaction that
   * overwrote the live segments in place would, if interrupted, leave a log that is neither the old
   * one nor the new one — the one state from which no recovery is possible.
   *
   * @param live the entries to retain, in the order they should be replayed
   * @return how many bytes the compacted log occupies
   */
  long compact(final List<JournalCodec.Entry> live) {
    Preconditions.requireNonNull(live, "live");
    final Path staging = directory.resolve("compact.tmp");
    try {
      Files.deleteIfExists(staging);
      try (FileChannel out =
          FileChannel.open(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        out.write(ByteBuffer.wrap(header()));
        for (final JournalCodec.Entry entry : live) {
          out.write(ByteBuffer.wrap(frame(JournalCodec.encode(entry))));
        }
        out.force(true);
      }

      // Close and drop the old segments only after the replacement is durable on disk.
      channel.close();
      for (final Path segment : segmentsInOrder()) {
        Files.deleteIfExists(segment);
      }
      segmentNumber = 1;
      activeSegment = segmentPath(segmentNumber);
      Files.move(staging, activeSegment, StandardCopyOption.ATOMIC_MOVE);
      openActive();
      return activeBytes;
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("journal compaction failed", failure);
    }
  }

  /**
   * Copies the current log into a directory, as a point-in-time snapshot.
   *
   * @param target where to copy the segments
   * @return how many segments were copied
   */
  int snapshotTo(final Path target) {
    Preconditions.requireNonNull(target, "target");
    try {
      flush();
      Files.createDirectories(target);
      int copied = 0;
      for (final Path segment : segmentsInOrder()) {
        Files.copy(
            segment,
            target.resolve(segment.getFileName().toString()),
            StandardCopyOption.REPLACE_EXISTING);
        copied++;
      }
      return copied;
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("journal snapshot failed", failure);
    }
  }

  /**
   * Returns how many times this log has forced data to disk.
   *
   * @return the sync count since the log was opened
   */
  long syncCount() {
    return syncs.get();
  }

  /**
   * Returns the total size of the log on disk.
   *
   * @return the byte count across every segment
   */
  long sizeBytes() {
    try {
      long total = 0L;
      for (final Path segment : segmentsInOrder()) {
        total += Files.size(segment);
      }
      return total;
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("cannot size journal", failure);
    }
  }

  /**
   * Returns how many segments the log currently spans.
   *
   * @return the segment count
   */
  int segmentCount() {
    return segmentsInOrder().size();
  }

  @Override
  public void close() {
    try {
      if (channel != null && channel.isOpen()) {
        channel.force(true);
        channel.close();
      }
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("cannot close journal", failure);
    }
  }

  // ---- recovery ---------------------------------------------------------------------------------

  private void recover(final Consumer<JournalCodec.Entry> onEntry) throws IOException {
    final List<Path> segments = segmentsInOrder();
    for (final Path segment : segments) {
      final long good = replaySegment(segment, onEntry);
      if (good < Files.size(segment)) {
        // A torn or corrupted tail. Truncate to the last good boundary so the next append lands at
        // a
        // valid offset rather than after garbage.
        truncate(segment, good);
        // Everything after a torn segment is unreachable in write order, so recovery stops here
        // rather than replaying entries that follow a gap.
        break;
      }
    }
    segmentNumber = segments.isEmpty() ? 1 : numberOf(segments.get(segments.size() - 1));
  }

  /**
   * Replays one segment, stopping at the first bad entry.
   *
   * @return the byte length of the prefix that is entirely good
   */
  private long replaySegment(final Path segment, final Consumer<JournalCodec.Entry> onEntry)
      throws IOException {

    final byte[] all = Files.readAllBytes(segment);
    int start = skipHeader(all);
    if (start == 0 && all.length > 0) {
      // No header: not a segment this store wrote, or its very first write was lost. Refusing to
      // interpret it is safer than guessing which tenant its entries belong to.
      return 0L;
    }
    long good = start;

    while (start < all.length) {
      int end = start;
      while (end < all.length && all[end] != '\n') {
        end++;
      }
      if (end == all.length) {
        break; // torn tail: no terminator
      }
      final String line = new String(all, start, end - start, StandardCharsets.UTF_8);
      final int separator = line.indexOf(CHECKSUM_SEPARATOR);
      if (separator <= 0) {
        break;
      }
      final String payload = line.substring(separator + 1);
      if (!checksumOf(payload).equals(line.substring(0, separator))) {
        break; // complete line, wrong checksum: a partially written sector containing a newline
      }
      try {
        onEntry.accept(JournalCodec.decode(payload));
      } catch (final JournalCorruptException corrupt) {
        // A well-framed entry this build cannot understand. Stopping is the honest response:
        // replaying
        // what follows would apply later entries on top of a state that skipped one.
        break;
      }
      start = end + 1;
      good = start;
    }
    return good;
  }

  private static void truncate(final Path segment, final long good) throws IOException {
    try (RandomAccessFile raf = new RandomAccessFile(segment.toFile(), "rw")) {
      raf.setLength(good);
      raf.getFD().sync();
    }
  }

  // ---- segments ---------------------------------------------------------------------------------

  private void openActive() throws IOException {
    activeSegment = segmentPath(segmentNumber);
    final boolean fresh = !Files.exists(activeSegment);
    channel =
        FileChannel.open(
            activeSegment,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND);
    if (fresh) {
      channel.write(ByteBuffer.wrap(header()));
      channel.force(true);
    }
    activeBytes = Files.size(activeSegment);
  }

  private void rotate() throws IOException {
    channel.force(true);
    channel.close();
    segmentNumber++;
    openActive();
  }

  private List<Path> segmentsInOrder() {
    final List<Path> segments = new ArrayList<>();
    try (DirectoryStream<Path> found =
        Files.newDirectoryStream(directory, SEGMENT_PREFIX + "*" + SEGMENT_SUFFIX)) {
      for (final Path segment : found) {
        segments.add(segment);
      }
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("cannot list journal segments", failure);
    }
    segments.sort((left, right) -> Integer.compare(numberOf(left), numberOf(right)));
    return segments;
  }

  private Path segmentPath(final int number) {
    return directory.resolve(String.format("%s%08d%s", SEGMENT_PREFIX, number, SEGMENT_SUFFIX));
  }

  private static int numberOf(final Path segment) {
    final String name = segment.getFileName().toString();
    return Integer.parseInt(
        name.substring(SEGMENT_PREFIX.length(), name.length() - SEGMENT_SUFFIX.length()));
  }

  // ---- framing ----------------------------------------------------------------------------------

  private static byte[] frame(final String payload) {
    return (checksumOf(payload) + CHECKSUM_SEPARATOR + payload + "\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static String checksumOf(final String payload) {
    final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    final CRC32 crc = new CRC32();
    crc.update(bytes, 0, bytes.length);
    return Long.toHexString(crc.getValue());
  }

  private static byte[] header() {
    return (HEADER_PREFIX + JournalCodec.FORMAT_VERSION + "\n").getBytes(StandardCharsets.UTF_8);
  }

  private static int skipHeader(final byte[] all) {
    final byte[] prefix = HEADER_PREFIX.getBytes(StandardCharsets.UTF_8);
    if (all.length < prefix.length) {
      return 0;
    }
    for (int i = 0; i < prefix.length; i++) {
      if (all[i] != prefix[i]) {
        return 0;
      }
    }
    for (int i = prefix.length; i < all.length; i++) {
      if (all[i] == '\n') {
        return i + 1;
      }
    }
    return 0;
  }
}
