package io.reliabilityai.gateway.dataplane.secrets.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import org.junit.jupiter.api.Test;

/**
 * The typed KMS failure, and the one property that is easy to lose.
 *
 * <p>{@code KmsException} is serializable whether or not this codebase wants it to be, because
 * {@code Throwable} is, and it declares a {@code serialVersionUID}. Its {@link KmsException.Reason}
 * was once {@code transient}, which meant a round-tripped instance came back with a {@code null}
 * reason the constructor promises is impossible. Nothing in this repository serializes anything
 * today, so the fault could only ever have surfaced later — on the error path, in whatever
 * transport or diagnostic tool first tried it.
 */
class KmsExceptionTest {

  @Test
  void theReasonSurvivesASerializationRoundTrip() throws Exception {
    final KmsException original =
        new KmsException(KmsException.Reason.INTEGRITY_FAILURE, "envelope authentication failed");

    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(original);
    }
    final KmsException restored;
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = (KmsException) in.readObject();
    }

    // The constructor's guarantee has to hold for every instance that exists, however it came to
    // exist. A null here is what the transient modifier produced.
    assertThat(restored.reason()).isEqualTo(KmsException.Reason.INTEGRITY_FAILURE);
    assertThat(restored.getMessage()).isEqualTo("envelope authentication failed");
  }

  @Test
  void everyReasonRoundTripsAsItself() throws Exception {
    // Enums serialize by name, so this also pins that no ordinal-based assumption creeps in.
    for (final KmsException.Reason reason : KmsException.Reason.values()) {
      final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
        out.writeObject(new KmsException(reason, "content-free"));
      }
      try (ObjectInputStream in =
          new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        assertThat(((KmsException) in.readObject()).reason()).isEqualTo(reason);
      }
    }
  }

  @Test
  void aReasonIsRequired() {
    // The invariant the transient modifier quietly broke.
    assertThatThrownBy(() -> new KmsException(null, "message"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("reason");
  }
}
