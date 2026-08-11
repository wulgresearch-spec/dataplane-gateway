package io.reliabilityai.gateway.ports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.ports.AuthenticationPort.AuthenticationResult;
import io.reliabilityai.gateway.ports.SecretsProviderPort.MaterializationResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Contract tests for port DTOs and fail-closed sealed results (Doc 25/26/37). */
class PortContractsTest {

  @Test
  void attemptBudgetRejectsNonPositiveTimeout() {
    assertThatThrownBy(() -> new AttemptBudget(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AttemptBudget(Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new AttemptBudget(Duration.ofMillis(500)).transportTimeout())
        .isEqualTo(Duration.ofMillis(500));
  }

  @Test
  void authenticationResultIsSealedWithTwoOutcomes() {
    assertThat(AuthenticationResult.class.isSealed()).isTrue();
    assertThat(AuthenticationResult.class.getPermittedSubclasses()).hasSize(2);
    final var unauth = new AuthenticationResult.Unauthenticated("unknown identity");
    assertThat(unauth.reason()).isEqualTo("unknown identity");
  }

  @Test
  void secretsMaterializationIsFailClosedSealed() {
    // Doc 26 §D11: fail-closed CredentialUnavailable outcome.
    assertThat(MaterializationResult.class.isSealed()).isTrue();
    final var unavailable = new MaterializationResult.CredentialUnavailable("cache miss");
    assertThat(unavailable.reason()).isEqualTo("cache miss");
  }
}
