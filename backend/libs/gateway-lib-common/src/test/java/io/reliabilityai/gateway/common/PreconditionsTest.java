package io.reliabilityai.gateway.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Preconditions} (Doc 35 §CmC primitives). */
class PreconditionsTest {

  @Test
  void requireNonNullRejectsNull() {
    assertThatThrownBy(() -> Preconditions.requireNonNull(null, "field"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("field");
  }

  @Test
  void requireNonBlankRejectsBlank() {
    assertThatThrownBy(() -> Preconditions.requireNonBlank("  ", "field"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("field");
  }

  @Test
  void immutableListCopiesAndIsUnmodifiable() {
    final var src = new java.util.ArrayList<>(List.of("a", "b"));
    final var copy = Preconditions.immutableList(src, "field");
    src.add("c");
    assertThat(copy).containsExactly("a", "b");
    assertThatThrownBy(() -> copy.add("x")).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void immutableMapCopiesAndIsUnmodifiable() {
    final var src = new java.util.HashMap<>(Map.of("k", "v"));
    final var copy = Preconditions.immutableMap(src, "field");
    src.put("k2", "v2");
    assertThat(copy).containsExactly(Map.entry("k", "v"));
  }

  @Test
  void requireNonNegativeRejectsNegative() {
    assertThatThrownBy(() -> Preconditions.requireNonNegative(-1, "field"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Preconditions.requireNonNegative(0, "field")).isZero();
  }
}
