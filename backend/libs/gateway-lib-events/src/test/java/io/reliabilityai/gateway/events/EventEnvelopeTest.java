package io.reliabilityai.gateway.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.common.ContentFree;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Contract tests for the event envelope (Doc 07 §6/§9). */
class EventEnvelopeTest {

  @Test
  void envelopeRequiresCorrelationAndCausation() {
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    "e1",
                    "rfaig.metering.usage.v1",
                    null,
                    new CausationId("c"),
                    Instant.EPOCH,
                    1,
                    null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("correlationId");
  }

  @Test
  void envelopeRejectsNonPositiveSchemaVersion() {
    assertThatThrownBy(
            () ->
                new EventEnvelope(
                    "e1",
                    "rfaig.metering.usage.v1",
                    new CorrelationId("x"),
                    new CausationId("c"),
                    Instant.EPOCH,
                    0,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void envelopeCarriesOptionalTenantScopeToken() {
    // M3: id-only, residency-safe tenant scope token; nullable (null before C6, Doc 27 §10.1).
    final var withToken =
        new EventEnvelope(
            "e1",
            "rfaig.metering.usage.v1",
            new CorrelationId("corr-1"),
            new CausationId("cause-1"),
            Instant.parse("2026-07-22T00:00:00Z"),
            1,
            "org-42:tenant-7");
    assertThat(withToken.tenantScopeToken()).isEqualTo("org-42:tenant-7");
  }

  @Test
  void envelopeIsContentFree() {
    assertThat(ContentFree.class.isAssignableFrom(EventEnvelope.class)).isTrue();
  }

  @Test
  void validEnvelopeExposesThreadedIdentity() {
    final var envelope =
        new EventEnvelope(
            "e1",
            "rfaig.metering.usage.v1",
            new CorrelationId("corr-1"),
            new CausationId("cause-1"),
            Instant.parse("2026-07-22T00:00:00Z"),
            1,
            null);
    assertThat(envelope.eventId()).isEqualTo("e1");
    assertThat(envelope.correlationId().value()).isEqualTo("corr-1");
    assertThat(envelope.causationId().value()).isEqualTo("cause-1");
    assertThat(envelope.schemaVersion()).isEqualTo(1);
  }
}
