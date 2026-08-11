package io.reliabilityai.gateway.dataplane.observability.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deny-by-default allow-list label policy tests (Doc 14 §7.1 OH-2, Doc 27 OT-A6). */
class MetricLabelPolicyTest {

  // The Doc-14-registered permitted low-cardinality label keys.
  private final MetricLabelPolicy policy =
      new MetricLabelPolicy(Set.of("outcome", "region", "canonical_model", "attempt_class"));

  @Test
  void allowsOnlyRegisteredNeutralLabels() {
    assertThat(policy.isAllowed(Map.of("outcome", "ok", "region", "us-east-1"))).isTrue();
  }

  @Test
  void emptyLabelsAllowed() {
    assertThat(policy.isAllowed(Map.of())).isTrue();
  }

  @Test
  void deniesUnregisteredKeyByDefaultIncludingContentBearingKeys() {
    // The core of the fix: a key that is neither registered nor pattern-detectable is DROPPED.
    assertThat(policy.isAllowed(Map.of("raw_prompt", "how do I ..."))).isFalse();
    assertThat(policy.isAllowed(Map.of("completion", "the answer is ..."))).isFalse();
    assertThat(policy.isAllowed(Map.of("some_new_unregistered_label", "x"))).isFalse();
  }

  @Test
  void rejectsForbiddenIdentifyingLabelKeysEvenIfRegistered() {
    final MetricLabelPolicy withForbidden =
        new MetricLabelPolicy(Set.of("outcome", "correlation_id", "api_key"));
    assertThat(withForbidden.isAllowed(Map.of("correlation_id", "x"))).isFalse();
    assertThat(withForbidden.isAllowed(Map.of("api_key", "x"))).isFalse();
  }

  @Test
  void allowListIsCaseInsensitive() {
    assertThat(policy.isAllowed(Map.of("Outcome", "ok"))).isTrue();
    assertThat(policy.isAllowed(Map.of("REGION", "eu"))).isTrue();
  }
}
