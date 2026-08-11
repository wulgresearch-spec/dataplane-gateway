package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.DeadLetterPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Single-VPS realization of {@link DeadLetterPort} (Doc 07): an append-only, node-local dead-letter
 * log. A record that exhausts retries or fails permanently is recorded here for out-of-band
 * handling; a zero-loss record additionally remains in the {@link LocalWal} for replay, so the DLQ
 * is visibility, not the loss boundary. Each line is <b>content-free</b> — {@code
 * eventId|topic|deliveryClass|reason} — never payload bytes (Doc 14 §7.1). Durable ({@code fsync}
 * on write) and append-only.
 *
 * <p>Thread- and virtual-thread-safe (a {@link ReentrantLock} around the append, never a monitor
 * across I/O, R-049). <b>AWS migration:</b> replace with an SQS/SNS or Kafka-DLQ adapter behind the
 * same port.
 */
public final class LocalFileDeadLetter implements DeadLetterPort, AutoCloseable {

  private final FileChannel channel;
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Opens (creating if absent) the append-only dead-letter log at {@code file}.
   *
   * @param file the dead-letter log path
   */
  public LocalFileDeadLetter(final Path file) {
    Preconditions.requireNonNull(file, "file");
    try {
      final Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      this.channel =
          FileChannel.open(
              file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    } catch (final IOException e) {
      throw new UncheckedIOException("dead-letter open failed", e);
    }
  }

  @Override
  public void deadLetter(final BrokerRecord record, final String reason) {
    Preconditions.requireNonNull(record, "record");
    Preconditions.requireNonBlank(reason, "reason");
    final String line =
        record.eventId()
            + '|'
            + record.topic()
            + '|'
            + record.deliveryClass().name()
            + '|'
            + reason
            + '\n';
    final byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
    lock.lock();
    try {
      channel.write(ByteBuffer.wrap(bytes));
      channel.force(false); // durable: a dead-lettered record survives a crash
    } catch (final IOException e) {
      throw new UncheckedIOException("dead-letter write failed", e);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void close() {
    lock.lock();
    try {
      channel.close();
    } catch (final IOException e) {
      throw new UncheckedIOException("dead-letter close failed", e);
    } finally {
      lock.unlock();
    }
  }
}
