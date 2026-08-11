package io.reliabilityai.gateway.common;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless validation primitives shared across contracts (Doc 35 §CmC — primitives only).
 *
 * <p>Contains no business, domain, framework, or stateful logic (CmC-2/CmC-3). Used by canonical
 * value objects to enforce never-null / non-blank invariants in their compact constructors.
 */
public final class Preconditions {

  private Preconditions() {}

  /**
   * Requires the value to be non-null.
   *
   * @param value the value
   * @param field the field name for the error message
   * @param <T> the value type
   * @return the non-null value
   */
  public static <T> T requireNonNull(final T value, final String field) {
    return Objects.requireNonNull(value, () -> field + " must not be null");
  }

  /**
   * Requires the string to be non-null and non-blank.
   *
   * @param value the string
   * @param field the field name for the error message
   * @return the trimmed, validated string
   */
  public static String requireNonBlank(final String value, final String field) {
    requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  /**
   * Returns a defensively-copied unmodifiable list (defends immutability; Doc 11 R-005).
   *
   * <p><b>Do not use this from a canonical record's compact constructor.</b> SpotBugs' {@code
   * EI_EXPOSE_REP} analysis is intra-procedural: it recognises {@code List.copyOf} only where the
   * call is inlined at the field assignment it is judging. A copy performed one frame down, in this
   * class, is invisible to it, so every accessor returning such a field is reported as exposing
   * internal representation. The finding is a false positive — the returned list genuinely is
   * unmodifiable — but it is indistinguishable from a real one, which is worse than useless in a
   * detector meant to catch real exposure. Canonical records therefore write {@code value == null ?
   * List.of() : List.copyOf(value)} inline, which is exactly what this method does.
   *
   * @param value the source list (may be null → empty)
   * @param field the field name for the error message
   * @param <T> the element type
   * @return an unmodifiable copy
   */
  public static <T> List<T> immutableList(final List<T> value, final String field) {
    requireNonNull(field, "field");
    return value == null ? List.of() : List.copyOf(value);
  }

  /**
   * Returns a defensively-copied unmodifiable map (defends immutability; Doc 11 R-005).
   *
   * <p><b>Do not use this from a canonical record's compact constructor</b>, for the reason given
   * on {@link #immutableList} — SpotBugs cannot see a copy made behind a method call and reports
   * the accessor as {@code EI_EXPOSE_REP}. Note also that the copy is <em>shallow</em>: for a map
   * whose values are themselves collections, freezing the map leaves those values writable through
   * the caller's reference, and the element type must be copied explicitly.
   *
   * @param value the source map (may be null → empty)
   * @param field the field name for the error message
   * @param <K> the key type
   * @param <V> the value type
   * @return an unmodifiable copy
   */
  public static <K, V> Map<K, V> immutableMap(final Map<K, V> value, final String field) {
    requireNonNull(field, "field");
    return value == null ? Map.of() : Map.copyOf(value);
  }

  /**
   * Requires the collection to be non-null and non-empty.
   *
   * @param value the collection
   * @param field the field name for the error message
   * @param <T> the collection type
   * @return the validated collection
   */
  public static <T extends Collection<?>> T requireNonEmpty(final T value, final String field) {
    requireNonNull(value, field);
    if (value.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be empty");
    }
    return value;
  }

  /**
   * Requires the numeric value to be non-negative.
   *
   * @param value the value
   * @param field the field name for the error message
   * @return the validated value
   */
  public static long requireNonNegative(final long value, final String field) {
    if (value < 0) {
      throw new IllegalArgumentException(field + " must be non-negative");
    }
    return value;
  }
}
