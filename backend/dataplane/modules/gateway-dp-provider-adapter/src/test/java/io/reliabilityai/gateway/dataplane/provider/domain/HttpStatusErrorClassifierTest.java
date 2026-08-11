package io.reliabilityai.gateway.dataplane.provider.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The frozen §16.1 HTTP-status → canonical-category mapping table (Doc 25). */
class HttpStatusErrorClassifierTest {

  @ParameterizedTest
  @CsvSource({
    "408,TIMEOUT",
    "504,TIMEOUT",
    "429,RATE_LIMITED",
    "401,AUTH_FAILED",
    "403,AUTH_FAILED",
    "500,PROVIDER_UNAVAILABLE",
    "502,PROVIDER_UNAVAILABLE",
    "503,PROVIDER_UNAVAILABLE",
    "400,PROVIDER_REJECTED",
    "404,PROVIDER_REJECTED",
    "422,PROVIDER_REJECTED"
  })
  void classifiesPer16dot1(final int status, final ErrorCategory expected) {
    assertThat(HttpStatusErrorClassifier.classify(status)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({"408,true", "429,true", "503,true", "401,false", "400,false"})
  void marksTransientPerCategory(final int status, final boolean transient_) {
    assertThat(HttpStatusErrorClassifier.isTransient(status)).isEqualTo(transient_);
  }

  @Test
  void buildsCanonicalErrorWithOpaqueCodeAndAdvisoryHint() {
    final CanonicalError error = HttpStatusErrorClassifier.toCanonicalError(503, "opaque-503");
    assertThat(error.category()).isEqualTo(ErrorCategory.PROVIDER_UNAVAILABLE);
    assertThat(error.providerCodeOpaque()).isEqualTo("opaque-503");
    assertThat(error.transientError()).isTrue();
    assertThat(error.retryableHint()).isTrue();
  }

  @Test
  void rejectsNonErrorStatus() {
    assertThatThrownBy(() -> HttpStatusErrorClassifier.classify(200))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
