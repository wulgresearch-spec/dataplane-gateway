package io.reliabilityai.gateway.dataplane.agent.internal;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.dataplane.agent.api.RunSerializer;
import io.reliabilityai.gateway.dataplane.agent.api.RunStoreUnavailableException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * A durable, append-only run store backed by one journal file per run.
 *
 * <p>Chosen over a database because the deployment target is a single Linux VPS with no server
 * dependencies, and because the store's whole contract is "append a line, read the lines back" —
 * which a file does natively and correctly, provided the two hard parts are handled honestly.
 *
 * <p><b>Hard part one: durability.</b> Every append is followed by an {@code fsync} before the
 * write is acknowledged (AD-025 HSC-4). Without it, an acknowledged append can be lost in the page
 * cache when the machine loses power, and the executor would have proceeded believing a step was
 * recorded.
 *
 * <p><b>Hard part two: torn writes.</b> Power can be lost part-way through a write, leaving a
 * partial final line. Each record is therefore stored as {@code <crc32>:<payload>\n}, and on open
 * the journal is read until the first record that is either unterminated or fails its checksum, at
 * which point the file is truncated to the last good boundary. A partial record is <em>discarded,
 * never repaired</em>: the two-phase step record means a lost tail is at worst an interrupted step,
 * which recovery already knows how to resolve, whereas a half-parsed record would be a fabricated
 * fact.
 *
 * <p><b>Scope limit, stated plainly.</b> Offsets and leases are held per-process, so this store is
 * correct for one node. Several nodes over a shared filesystem would each keep their own view of a
 * run's length and could both win an append. A multi-node deployment needs a store whose
 * conditional append is enforced centrally; the port is shaped so that swapping one in changes
 * nothing above it.
 */
public final class JournalRunRepository implements RunRepository {

  private static final char CHECKSUM_SEPARATOR = ':';
  private static final String SUFFIX = ".jrnl";

  /**
   * The first line of every journal, naming the run it belongs to.
   *
   * <p>The file name cannot carry that job. Run ids are caller-supplied strings, so the name has to
   * be sanitised to stay inside the root — and sanitising is lossy, which means two different run
   * ids can produce one file name and neither can be read back verbatim. Since step identities are
   * stored relative to their run, recovering under the wrong id would silently rewrite every step
   * in the history. The header is authoritative; the file name is a convenience for humans.
   *
   * <p>It is not a record, so it occupies no offset and the conditional-append arithmetic is
   * unaffected.
   */
  private static final String HEADER_PREFIX = "#run=";

  private static final class Journal {
    private final Path path;
    private final TenantScope tenant;
    private final Object lock = new Object();
    private final List<RunEvent> events = new ArrayList<>();
    private String leaseOwner;
    private Instant leaseUntil;
    private boolean terminated;
    private Instant wakeAt;

    Journal(final Path path, final TenantScope tenant) {
      this.path = path;
      this.tenant = tenant;
    }
  }

  private final Path root;
  private final RunSerializer serializer;
  private final Map<RunId, Journal> journals = new ConcurrentHashMap<>();

  /**
   * Opens a store, recovering every journal already present.
   *
   * <p>Recovery happens at construction rather than lazily, so a node that starts with a corrupt
   * journal finds out immediately instead of during the first run that touches it.
   *
   * @param root the directory to hold journals under; created if absent
   * @param serializer the journal codec
   */
  public JournalRunRepository(final Path root, final RunSerializer serializer) {
    this.root = Preconditions.requireNonNull(root, "root");
    this.serializer = Preconditions.requireNonNull(serializer, "serializer");
    try {
      Files.createDirectories(root);
    } catch (final IOException failure) {
      throw new RunStoreUnavailableException("cannot create journal root " + root, failure);
    }
    recoverAll();
  }

  @Override
  public AppendResult create(final RunId runId, final RunEvent.RunCreated created) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(created, "created");
    final TenantScope tenant = created.security().tenant();
    final Path path = pathFor(tenant, runId);

    final Journal journal = new Journal(path, tenant);
    final Journal existing = journals.putIfAbsent(runId, journal);
    if (existing != null) {
      synchronized (existing.lock) {
        return new AppendResult.Conflict(existing.events.size());
      }
    }
    synchronized (journal.lock) {
      try {
        Files.createDirectories(path.getParent());
      } catch (final IOException failure) {
        journals.remove(runId);
        return new AppendResult.Unavailable("cannot create tenant directory: " + failure);
      }
      final AppendResult header = writeHeader(journal, runId);
      if (!(header instanceof AppendResult.Appended)) {
        journals.remove(runId);
        return header;
      }
      return write(journal, created);
    }
  }

  @Override
  public AppendResult append(final RunId runId, final long expectedOffset, final RunEvent event) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(event, "event");
    Preconditions.requireNonNegative(expectedOffset, "expectedOffset");
    final Journal journal = journals.get(runId);
    if (journal == null) {
      return new AppendResult.Conflict(0L);
    }
    synchronized (journal.lock) {
      if (journal.events.size() != expectedOffset) {
        return new AppendResult.Conflict(journal.events.size());
      }
      return write(journal, event);
    }
  }

  /** Writes the run-id header. Called exactly once, before the creation record. */
  private AppendResult writeHeader(final Journal journal, final RunId runId) {
    final String line = HEADER_PREFIX + escapeHeader(runId.value()) + "\n";
    try (var channel =
        java.nio.channels.FileChannel.open(
            journal.path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND)) {
      channel.write(java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
      channel.force(true);
    } catch (final IOException failure) {
      return new AppendResult.Unavailable("journal header write failed: " + failure);
    }
    return new AppendResult.Appended(0L);
  }

  /**
   * Serialises, checksums, appends and fsyncs. The in-memory view advances only after the fsync.
   */
  private AppendResult write(final Journal journal, final RunEvent event) {
    final String payload = serializer.serialize(event);
    final CRC32 crc = new CRC32();
    final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    crc.update(bytes, 0, bytes.length);
    final String record = Long.toHexString(crc.getValue()) + CHECKSUM_SEPARATOR + payload + "\n";

    try (var channel =
        java.nio.channels.FileChannel.open(
            journal.path,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND)) {
      channel.write(java.nio.ByteBuffer.wrap(record.getBytes(StandardCharsets.UTF_8)));
      // Acknowledge only what the disk has. AD-025 HSC-4: an unacknowledged append must be treated
      // as
      // not having happened, and acknowledging a page-cache write makes that promise false.
      channel.force(true);
    } catch (final IOException failure) {
      return new AppendResult.Unavailable("journal write failed: " + failure);
    }

    journal.events.add(event);
    if (event instanceof RunEvent.RunTerminated) {
      journal.terminated = true;
    }
    if (event instanceof RunEvent.RunParked parked) {
      journal.wakeAt = parked.wakeAt();
    } else if (!(event instanceof RunEvent.RunCheckpointed)) {
      journal.wakeAt = null;
    }
    return new AppendResult.Appended(journal.events.size());
  }

  @Override
  public Optional<RunHistory> load(final RunId runId) {
    Preconditions.requireNonNull(runId, "runId");
    final Journal journal = journals.get(runId);
    if (journal == null) {
      return Optional.empty();
    }
    synchronized (journal.lock) {
      return Optional.of(new RunHistory(runId, List.copyOf(journal.events)));
    }
  }

  @Override
  public List<RunId> claimable(final TenantScope tenant, final Instant now, final int limit) {
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonNull(now, "now");
    final List<RunId> claimableRuns = new ArrayList<>();
    for (final Map.Entry<RunId, Journal> candidate : journals.entrySet()) {
      if (claimableRuns.size() >= limit) {
        break;
      }
      final Journal journal = candidate.getValue();
      synchronized (journal.lock) {
        if (!journal.tenant.equals(tenant) || journal.terminated) {
          continue;
        }
        if (journal.wakeAt != null && now.isBefore(journal.wakeAt)) {
          continue;
        }
        if (leaseHeld(journal, now)) {
          continue;
        }
      }
      claimableRuns.add(candidate.getKey());
    }
    return List.copyOf(claimableRuns);
  }

  @Override
  public List<RunId> unfinished(final TenantScope tenant, final int limit) {
    Preconditions.requireNonNull(tenant, "tenant");
    final List<RunId> open = new ArrayList<>();
    for (final Map.Entry<RunId, Journal> candidate : journals.entrySet()) {
      if (open.size() >= limit) {
        break;
      }
      final Journal journal = candidate.getValue();
      synchronized (journal.lock) {
        if (!journal.tenant.equals(tenant) || journal.terminated) {
          continue;
        }
      }
      open.add(candidate.getKey());
    }
    return List.copyOf(open);
  }

  @Override
  public boolean claim(
      final RunId runId, final String owner, final Instant now, final Instant until) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonBlank(owner, "owner");
    Preconditions.requireNonNull(now, "now");
    Preconditions.requireNonNull(until, "until");
    final Journal journal = journals.get(runId);
    if (journal == null) {
      return false;
    }
    synchronized (journal.lock) {
      if (leaseHeld(journal, now)) {
        return false;
      }
      journal.leaseOwner = owner;
      journal.leaseUntil = until;
      return true;
    }
  }

  @Override
  public void release(final RunId runId, final String owner) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(owner, "owner");
    final Journal journal = journals.get(runId);
    if (journal == null) {
      return;
    }
    synchronized (journal.lock) {
      if (owner.equals(journal.leaseOwner)) {
        journal.leaseOwner = null;
        journal.leaseUntil = null;
      }
    }
  }

  /**
   * Returns how many runs this store holds.
   *
   * @return the run count
   */
  public int size() {
    return journals.size();
  }

  /** Re-reads every journal from disk, truncating any torn tail. */
  private void recoverAll() {
    try (DirectoryStream<Path> tenants = Files.newDirectoryStream(root)) {
      for (final Path tenantDir : tenants) {
        if (Files.isDirectory(tenantDir)) {
          recoverTenant(tenantDir);
        }
      }
    } catch (final IOException failure) {
      throw new RunStoreUnavailableException("cannot scan journal root " + root, failure);
    }
  }

  private void recoverTenant(final Path tenantDir) throws IOException {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(tenantDir, "*" + SUFFIX)) {
      for (final Path file : files) {
        recoverJournal(file);
      }
    }
  }

  private void recoverJournal(final Path file) throws IOException {
    final Optional<RunId> declared = readHeader(file);
    if (declared.isEmpty()) {
      // No header means the file was not written by this store, or its very first write was lost.
      // Guessing the id from the file name risks recovering a run under a colliding identity.
      return;
    }
    final RunId runId = declared.get();

    final List<String> good = new ArrayList<>();
    final long goodBytes = readGoodRecords(file, good);

    if (good.isEmpty()) {
      // Nothing survived. A journal with no readable creation record is not a run; leaving the file
      // in place and refusing to register it is safer than inventing a run to attach it to.
      return;
    }

    final List<RunEvent> events = new ArrayList<>(good.size());
    for (final String payload : good) {
      events.add(serializer.deserialize(payload, runId));
    }

    if (!(events.get(0) instanceof RunEvent.RunCreated created)) {
      return;
    }

    truncateIfTorn(file, goodBytes);

    final Journal journal = new Journal(file, created.security().tenant());
    journal.events.addAll(events);
    for (final RunEvent event : events) {
      if (event instanceof RunEvent.RunTerminated) {
        journal.terminated = true;
      }
      if (event instanceof RunEvent.RunParked parked) {
        journal.wakeAt = parked.wakeAt();
      } else if (!(event instanceof RunEvent.RunCheckpointed)) {
        journal.wakeAt = null;
      }
    }
    journals.put(runId, journal);
  }

  /**
   * Reads records until the first bad one.
   *
   * @return the byte length of the prefix that is entirely good
   */
  private static long readGoodRecords(final Path file, final List<String> into) throws IOException {
    final byte[] all = Files.readAllBytes(file);
    int start = skipHeader(all);
    long goodBytes = start;
    while (start < all.length) {
      int end = start;
      while (end < all.length && all[end] != '\n') {
        end++;
      }
      if (end == all.length) {
        // No terminating newline: the process died mid-write. Everything before this point is
        // intact.
        break;
      }
      final String line = new String(all, start, end - start, StandardCharsets.UTF_8);
      final int sep = line.indexOf(CHECKSUM_SEPARATOR);
      if (sep <= 0) {
        break;
      }
      final String payload = line.substring(sep + 1);
      final byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
      final CRC32 crc = new CRC32();
      crc.update(payloadBytes, 0, payloadBytes.length);
      if (!Long.toHexString(crc.getValue()).equals(line.substring(0, sep))) {
        // A complete line whose checksum disagrees. Rare, but it happens when a sector is written
        // partially and the tail happens to contain a newline. Stop here rather than trust it.
        break;
      }
      into.add(payload);
      start = end + 1;
      goodBytes = start;
    }
    return goodBytes;
  }

  private static void truncateIfTorn(final Path file, final long goodBytes) throws IOException {
    if (Files.size(file) == goodBytes) {
      return;
    }
    try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
      raf.setLength(goodBytes);
      raf.getFD().sync();
    }
  }

  /**
   * Chooses the file a run's journal lives in.
   *
   * <p>Sanitised for containment, then suffixed with a digest of the raw id. Sanitising alone is
   * not injective — {@code a/b} and {@code a-b} collapse to the same name — and two runs sharing
   * one journal would interleave their histories irrecoverably.
   */
  private Path pathFor(final TenantScope tenant, final RunId runId) {
    return root.resolve(sanitize(tenant.org() + "-" + tenant.tenant()))
        .resolve(
            sanitize(runId.value())
                + "-"
                + io.reliabilityai.gateway.dataplane.agent.domain.Digest.of(runId.value())
                    .substring(0, 8)
                + SUFFIX);
  }

  /** Reads the run id the journal declares for itself. */
  private static Optional<RunId> readHeader(final Path file) throws IOException {
    final byte[] all = Files.readAllBytes(file);
    final int end = skipHeader(all);
    if (end == 0) {
      return Optional.empty();
    }
    final String line = new String(all, 0, end - 1, StandardCharsets.UTF_8);
    final String raw = unescapeHeader(line.substring(HEADER_PREFIX.length()));
    return raw.isEmpty() ? Optional.empty() : Optional.of(RunId.of(raw));
  }

  /**
   * Returns the byte offset just past the header line, or zero when there is no complete header.
   */
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
      if (all[i] == 10) {
        return i + 1;
      }
    }
    return 0;
  }

  /** Escapes only what would break a single-line header. */
  private static String escapeHeader(final String raw) {
    return raw.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
  }

  private static String unescapeHeader(final String encoded) {
    final StringBuilder out = new StringBuilder(encoded.length());
    for (int i = 0; i < encoded.length(); i++) {
      final char c = encoded.charAt(i);
      if (c != '\\' || i + 1 >= encoded.length()) {
        out.append(c);
        continue;
      }
      final char next = encoded.charAt(++i);
      out.append(next == 'n' ? '\n' : next == 'r' ? '\r' : next);
    }
    return out.toString();
  }

  /**
   * Makes an identifier safe as a path segment.
   *
   * <p>Anything outside a conservative alphabet becomes an underscore. Run ids and tenant names are
   * caller-supplied strings, and a caller who supplies {@code ../../etc} must not be able to choose
   * where the journal lands.
   */
  private static String sanitize(final String raw) {
    final StringBuilder safe = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      final char c = raw.charAt(i);
      // No '.' in the alphabet at all. The suffix is appended outside this method, so a dot is
      // never needed here, and allowing one lets ".." survive into a path segment.
      final boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '-'
              || c == '_';
      safe.append(ok ? c : '_');
    }
    return safe.toString();
  }

  private static boolean leaseHeld(final Journal journal, final Instant now) {
    return journal.leaseOwner != null
        && journal.leaseUntil != null
        && now.isBefore(journal.leaseUntil);
  }
}
