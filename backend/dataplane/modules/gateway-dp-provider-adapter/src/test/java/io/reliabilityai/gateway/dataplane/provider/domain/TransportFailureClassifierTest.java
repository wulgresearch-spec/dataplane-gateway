package io.reliabilityai.gateway.dataplane.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Neutral transport-failure-kind → canonical-error mapping (Doc 25 §16.1). */
class TransportFailureClassifierTest {

  @ParameterizedTest
  @CsvSource({
    "CONNECT,TRANSPORT",
    "TLS,TRANSPORT",
    "IO,TRANSPORT",
    "TIMEOUT,TIMEOUT",
    "CANCELLED,TIMEOUT"
  })
  void classifiesKind(final TransportFailureKind kind, final ErrorCategory expected) {
    final CanonicalError error = TransportFailureClassifier.classify(kind, "opaque");
    assertThat(error.category()).isEqualTo(expected);
    assertThat(error.transientError()).isTrue(); // transport failures are advisorily transient
    assertThat(error.providerCodeOpaque()).isEqualTo("opaque");
  }
}
