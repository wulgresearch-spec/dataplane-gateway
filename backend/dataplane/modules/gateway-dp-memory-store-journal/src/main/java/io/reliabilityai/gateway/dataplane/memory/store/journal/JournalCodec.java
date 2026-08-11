package io.reliabilityai.gateway.dataplane.memory.store.journal;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryContent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Turns journal entries into single lines of bytes and back.
 *
 * <p><b>Explicit, never reflective.</b> Every field is named and written by hand. A reflective
 * codec makes the on-disk grammar a consequence of a class shape rather than a decision anyone
 * reviewed, and turns every field rename into a silent data migration.
 *
 * <p><b>One line per entry, with nothing unescaped that could end one.</b> The recovery scan finds
 * the end of the last good entry by looking for a newline, so a payload containing a raw newline
 * would split one entry into two and corrupt every offset after it. Escaping is therefore a
 * durability property here, not a formatting nicety.
 *
 * <p><b>Round-trip fidelity is a correctness requirement</b> (AD-026 A6, "stored bytes are returned
 * byte-identical"). The conformance suite asserts it over every field of every record.
 */
final class JournalCodec {

  /**
   * The on-disk grammar version. A change needs a reader for both, or old journals become
   * unreadable.
   */
  static final int FORMAT_VERSION = 1;

  private static final char FIELD = '|';
  private static final char ASSIGN = '=';
  private static final char ESCAPE = '\\';

  /**
   * What an entry does. Stored as a stable tag, not a class name — renaming a class is not a
   * migration.
   */
  enum Kind {
    /** A record was written or replaced. */
    PUT("put"),
    /** A record was removed. A tombstone: the log is append-only, so deletion is an addition. */
    DELETE("del"),
    /**
     * A record was read. Carries only the access counters, because a full record would be wasteful.
     */
    TOUCH("touch");

    private final String tag;

    Kind(final String tag) {
      this.tag = tag;
    }

    String tag() {
      return tag;
    }

    static Kind of(final String tag) {
      for (final Kind kind : values()) {
        if (kind.tag.equals(tag)) {
          return kind;
        }
      }
      throw new JournalCorruptException("unknown entry kind: " + tag);
    }
  }

  /**
   * One decoded journal entry.
   *
   * @param kind what it does
   * @param recordId which record it concerns
   * @param record the full record, present only for {@link Kind#PUT}
   * @param idempotencyKey the write key, present only for {@link Kind#PUT}
   * @param touchedAt the access instant, present only for {@link Kind#TOUCH}
   * @param accessCount the access count, meaningful only for {@link Kind#TOUCH}
   */
  record Entry(
      Kind kind,
      MemoryRecordId recordId,
      Optional<MemoryRecord> record,
      Optional<String> idempotencyKey,
      Optional<Instant> touchedAt,
      long accessCount) {

    static Entry put(final MemoryRecord record, final String idempotencyKey) {
      return new Entry(
          Kind.PUT,
          record.id(),
          Optional.of(record),
          Optional.of(idempotencyKey),
          Optional.empty(),
          0L);
    }

    static Entry delete(final MemoryRecordId id) {
      return new Entry(Kind.DELETE, id, Optional.empty(), Optional.empty(), Optional.empty(), 0L);
    }

    static Entry touch(final MemoryRecordId id, final Instant at, final long count) {
      return new Entry(Kind.TOUCH, id, Optional.empty(), Optional.empty(), Optional.of(at), count);
    }
  }

  private JournalCodec() {
    throw new AssertionError("no instances");
  }

  /**
   * Encodes one entry.
   *
   * @param entry the entry to encode
   * @return a single line containing no newline
   */
  static String encode(final Entry entry) {
    Preconditions.requireNonNull(entry, "entry");
    final StringBuilder out = new StringBuilder(256);
    out.append(entry.kind().tag());
    put(out, "id", entry.recordId().value());

    switch (entry.kind()) {
      case PUT -> {
        final MemoryRecord record = entry.record().orElseThrow();
        put(out, "wkey", entry.idempotencyKey().orElseThrow());
        put(out, "org", record.scope().tenant().org());
        put(out, "tenant", record.scope().tenant().tenant());
        put(out, "ws", nullToMarker(record.scope().tenant().workspace()));
        put(out, "proj", nullToMarker(record.scope().tenant().project()));
        put(out, "owner", record.scope().owner().map(PrincipalId::value).orElse(null));
        put(out, "part", record.scope().partition());
        put(out, "type", record.type().name());
        put(out, "body", record.content().body());
        put(out, "digest", record.content().digest());
        putBool(out, "sealed", record.content().sealed());
        put(out, "keyref", record.content().keyRef().orElse(null));
        putLong(out, "size", record.content().sizeBytes());
        put(out, "class", record.classification().name());
        put(out, "meta", encodeMetadata(record.metadata()));
        put(out, "created", record.createdAt().toString());
        put(out, "expires", record.expiresAt().map(Instant::toString).orElse(null));
        putLong(out, "ver", record.version());
        put(out, "imp", Double.toString(record.importance()));
        putBool(out, "taint", record.tainted());
        putBool(out, "hold", record.legalHold());
        putBool(out, "arch", record.archived());
        putBool(out, "idxp", record.indexPending());
        put(out, "seen", record.lastAccessedAt().map(Instant::toString).orElse(null));
        putLong(out, "count", record.accessCount());
      }
      case TOUCH -> {
        put(out, "at", entry.touchedAt().orElseThrow().toString());
        putLong(out, "count", entry.accessCount());
      }
      case DELETE -> {
        // The identity is the whole entry. A tombstone that carried content would keep a copy of
        // the
        // thing the deletion was supposed to remove.
      }
    }
    return out.toString();
  }

  /**
   * Decodes one entry.
   *
   * @param line a line previously produced by {@link #encode}
   * @return the decoded entry
   * @throws JournalCorruptException when the line is malformed; never a partially-populated entry,
   *     because a half-decoded fact is worse than an absent one
   */
  static Entry decode(final String line) {
    Preconditions.requireNonNull(line, "line");
    final int split = line.indexOf(FIELD);
    final String tag = split < 0 ? line : line.substring(0, split);
    final Map<String, String> fields = split < 0 ? Map.of() : parse(line.substring(split + 1));
    final Kind kind = Kind.of(tag);
    final MemoryRecordId id = MemoryRecordId.of(text(fields, "id"));

    return switch (kind) {
      case DELETE -> Entry.delete(id);
      case TOUCH -> Entry.touch(id, instant(fields, "at"), longValue(fields, "count"));
      case PUT -> Entry.put(decodeRecord(id, fields), text(fields, "wkey"));
    };
  }

  private static MemoryRecord decodeRecord(
      final MemoryRecordId id, final Map<String, String> fields) {

    final MemoryScope scope =
        new MemoryScope(
            new TenantScope(
                text(fields, "org"),
                text(fields, "tenant"),
                markerToNull(text(fields, "ws")),
                markerToNull(text(fields, "proj"))),
            optional(fields, "owner").map(PrincipalId::new),
            text(fields, "part"));

    final boolean sealed = boolValue(fields, "sealed");
    final MemoryContent content =
        sealed
            ? MemoryContent.sealed(
                text(fields, "body"),
                text(fields, "digest"),
                optional(fields, "keyref")
                    .orElseThrow(() -> new JournalCorruptException("sealed entry has no key ref")),
                (int) longValue(fields, "size"))
            : MemoryContent.plain(text(fields, "body"), text(fields, "digest"));

    return new MemoryRecord(
        id,
        scope,
        MemoryType.valueOf(text(fields, "type")),
        content,
        DataClassification.valueOf(text(fields, "class")),
        decodeMetadata(text(fields, "meta")),
        instant(fields, "created"),
        optional(fields, "expires").map(Instant::parse),
        (int) longValue(fields, "ver"),
        Double.parseDouble(text(fields, "imp")),
        boolValue(fields, "taint"),
        boolValue(fields, "hold"),
        boolValue(fields, "arch"),
        boolValue(fields, "idxp"),
        optional(fields, "seen").map(Instant::parse),
        longValue(fields, "count"));
  }

  /**
   * Encodes metadata as a nested escaped map.
   *
   * <p>Sorted, so two nodes encoding the same record produce byte-identical lines. Without that a
   * digest over a journal would be meaningless and a replicated store could not be compared.
   */
  private static String encodeMetadata(final Map<String, String> metadata) {
    final StringBuilder out = new StringBuilder();
    for (final Map.Entry<String, String> entry : new TreeMap<>(metadata).entrySet()) {
      if (out.length() > 0) {
        out.append(FIELD);
      }
      out.append(escape(entry.getKey())).append(ASSIGN).append(escape(entry.getValue()));
    }
    return out.toString();
  }

  private static Map<String, String> decodeMetadata(final String encoded) {
    if (encoded.isEmpty()) {
      return Map.of();
    }
    return parse(encoded);
  }

  // ---- field helpers --------------------------------------------------------------------------

  private static void put(final StringBuilder out, final String key, final String value) {
    out.append(FIELD).append(key).append(ASSIGN).append(value == null ? "" : escape(value));
  }

  private static void putLong(final StringBuilder out, final String key, final long value) {
    put(out, key, Long.toString(value));
  }

  private static void putBool(final StringBuilder out, final String key, final boolean value) {
    put(out, key, value ? "1" : "0");
  }

  private static String text(final Map<String, String> fields, final String key) {
    final String value = fields.get(key);
    if (value == null) {
      throw new JournalCorruptException("missing field '" + key + "'");
    }
    return value;
  }

  /** An absent optional and an empty string are the same on disk, and both decode to empty. */
  private static Optional<String> optional(final Map<String, String> fields, final String key) {
    final String value = fields.get(key);
    return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
  }

  private static long longValue(final Map<String, String> fields, final String key) {
    try {
      return Long.parseLong(text(fields, key));
    } catch (final NumberFormatException malformed) {
      throw new JournalCorruptException("field '" + key + "' is not a number", malformed);
    }
  }

  private static boolean boolValue(final Map<String, String> fields, final String key) {
    return "1".equals(text(fields, key));
  }

  private static Instant instant(final Map<String, String> fields, final String key) {
    try {
      return Instant.parse(text(fields, key));
    } catch (final java.time.format.DateTimeParseException malformed) {
      throw new JournalCorruptException("field '" + key + "' is not an instant", malformed);
    }
  }

  /**
   * Encodes a possibly-null workspace or project with an explicit presence prefix.
   *
   * <p>Null differs from empty in a {@code TenantScope} — two scopes differing only there must not
   * collapse into one — so absence has to survive the round trip. An in-band sentinel character
   * would do it, and the first version of this codec used one; that was wrong twice over. A
   * sentinel can collide with a real value, and the character chosen turned out to be a control
   * byte, which put unprintable bytes into a line-oriented journal and into anything that read it.
   *
   * <p>A leading {@code -} or {@code +} cannot collide, because it is never part of the value.
   */
  private static String nullToMarker(final String value) {
    return value == null ? "-" : "+" + value;
  }

  private static String markerToNull(final String value) {
    if (value.isEmpty()) {
      throw new JournalCorruptException("presence-prefixed field is empty");
    }
    return value.charAt(0) == '-' ? null : value.substring(1);
  }

  // ---- escaping -------------------------------------------------------------------------------

  private static String escape(final String value) {
    final StringBuilder escaped = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case ESCAPE -> escaped.append("\\\\");
        case FIELD -> escaped.append("\\p");
        case ASSIGN -> escaped.append("\\e");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private static Map<String, String> parse(final String body) {
    final Map<String, String> fields = new LinkedHashMap<>();
    final StringBuilder token = new StringBuilder();
    String key = null;
    boolean escaped = false;

    for (int i = 0; i < body.length(); i++) {
      final char c = body.charAt(i);
      if (escaped) {
        token.append(unescape(c));
        escaped = false;
        continue;
      }
      switch (c) {
        case ESCAPE -> escaped = true;
        case ASSIGN -> {
          if (key == null) {
            key = token.toString();
            token.setLength(0);
          } else {
            throw new JournalCorruptException("unescaped '=' in value for key '" + key + "'");
          }
        }
        case FIELD -> {
          if (key == null) {
            throw new JournalCorruptException("field separator before any key");
          }
          fields.put(key, token.toString());
          token.setLength(0);
          key = null;
        }
        default -> token.append(c);
      }
    }
    if (escaped) {
      throw new JournalCorruptException("entry ends with a dangling escape");
    }
    if (key != null) {
      fields.put(key, token.toString());
    }
    return fields;
  }

  private static char unescape(final char c) {
    return switch (c) {
      case '\\' -> ESCAPE;
      case 'p' -> FIELD;
      case 'e' -> ASSIGN;
      case 'n' -> '\n';
      case 'r' -> '\r';
      default -> throw new JournalCorruptException("unknown escape: \\" + c);
    };
  }
}
