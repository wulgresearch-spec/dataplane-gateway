package io.reliabilityai.gateway.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.reliabilityai.gateway.canonical.audit.AuditRecord;
import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.io.ProviderMeta;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.canonical.snapshot.ProviderCapabilitiesSnapshot;
import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.common.ContentFree;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Contract tests for the canonical model (Doc 33 §CMA, §TIC, §4, §10.7). */
class CanonicalModelContractTest {

  private static final JavaClasses CANONICAL =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.reliabilityai.gateway.canonical");

  /**
   * The deliberate concrete non-record value types. Both need value semantics a record cannot
   * express; neither is an exemption of convenience.
   *
   * <p><b>ProviderMeta</b> — an opaque byte holder needing custom value equality and byte-array
   * cloning a record cannot provide (Doc 33 §10.2, Doc 25 §7.1).
   *
   * <p><b>CapabilitySet</b> — its {@code equals}/{@code hashCode} compare the backing {@code
   * EnumSet} alone and deliberately ignore {@code tokens}, which is a derived projection of the
   * same state. A record's generated equality compares every component, so two identical capability
   * sets would compare unequal whenever their token projections differed. The backing set is also a
   * mutable {@code EnumSet} copied on construction and never handed out, which a record accessor
   * would expose directly.
   *
   * <p>This entry was added when the ArchUnit suite first became executable; the rule had never run
   * before, so the violation is newly surfaced rather than newly introduced.
   */
  private static final Set<String> JUSTIFIED_NON_RECORDS =
      Set.of(
          "io.reliabilityai.gateway.canonical.io.ProviderMeta",
          "io.reliabilityai.gateway.canonical.capability.CapabilitySet");

  @Test
  void identityValueObjectsRejectBlank() {
    assertThatThrownBy(() -> new CorrelationId(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new IdempotencyKey("")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void executionIdentityRequiresAllComponents() {
    assertThatThrownBy(
            () -> new ExecutionIdentity(new IdempotencyKey("k"), new AttemptId("a"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("correlationId");
  }

  @Test
  void canonicalRequestDefensivelyCopiesCollections() {
    final var messages = new ArrayList<>(List.of(new Message("user", "hi")));
    final var request = new CanonicalRequest(new CanonicalModelId("m1"), messages, List.of(), null);
    messages.add(new Message("user", "mutation"));
    assertThat(request.messages()).hasSize(1);
    assertThatThrownBy(() -> request.messages().add(new Message("user", "x")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(request.params()).isEmpty();
  }

  @Test
  void providerCapabilitiesSnapshotCopiesTheListsInsideTheMapNotJustTheMap() {
    // ProviderCapabilitiesSnapshot is the only canonical type whose map values are themselves
    // collections, and it is the only place where "defensively copied" was not the whole truth:
    // Map.copyOf froze the outer map while leaving every capability list writable through the
    // caller's own reference. The runtime consumes capabilities read-only and never authors them
    // (Doc 25 §14.1 CAP-1..6), so exposure one level down defeats the contract just as completely.
    final List<String> caps = new ArrayList<>(List.of("streaming"));
    final Map<String, List<String>> source = new HashMap<>();
    source.put("m1", caps);
    final var snapshot =
        new ProviderCapabilitiesSnapshot(new SnapshotVersion("providers", "1"), source);

    caps.add("tools");
    source.put("m2", new ArrayList<>(List.of("injected")));

    assertThat(snapshot.capabilities()).containsOnlyKeys("m1");
    assertThat(snapshot.capabilities().get("m1")).containsExactly("streaming");

    // Both levels of the returned structure must refuse writes, not just the outer map.
    assertThatThrownBy(() -> snapshot.capabilities().put("m3", List.of()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> snapshot.capabilities().get("m1").add("tools"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void credentialLeaseIsNeverSerializableAndIsAutoCloseable() {
    // Doc 26 §17.1/§20.1: credentials never serialized/captured; lease is AutoCloseable (zeroize).
    assertThat(Serializable.class.isAssignableFrom(CredentialLease.class)).isFalse();
    assertThat(AutoCloseable.class.isAssignableFrom(CredentialLease.class)).isTrue();
  }

  @Test
  void factsAndAuditAreContentFree() {
    // Doc 14 §7.1 / Doc 33 §10.5: facts and audit records carry only ids/counts, never content.
    assertThat(ContentFree.class.isAssignableFrom(UsageFact.class)).isTrue();
    assertThat(ContentFree.class.isAssignableFrom(AuditRecord.class)).isTrue();
  }

  @Test
  void everyConcreteCanonicalTypeIsRecordEnumOrJustified() {
    // Doc 33 §4 (M7 fix — scans the WHOLE package, not a hardcoded subset): every concrete
    // canonical
    // type is an immutable record or enum. Interfaces (CredentialLease, sealed stream/plugin/result
    // hierarchies) and abstract sealed bases carry no state and are exempt; ProviderMeta is the one
    // justified non-record value type.
    for (final JavaClass jc : CANONICAL) {
      final Class<?> type = jc.reflect();
      if (type.isInterface()
          || type.isAnnotation()
          || type.isEnum()
          || type.isRecord()
          || Modifier.isAbstract(type.getModifiers())) {
        continue;
      }
      assertThat(JUSTIFIED_NON_RECORDS)
          .as("%s is a concrete canonical type but is neither a record nor enum", type.getName())
          .contains(type.getName());
      assertThat(hasPublicSetter(type)).as("%s must have no setters", type.getName()).isFalse();
    }
  }

  @Test
  void canonicalResponseRequiresProviderMeta() {
    // H1: Doc 33 §10.2 — CanonicalResponse carries opaque providerMeta; it is mandatory (never
    // null).
    final CanonicalUsage usage = new CanonicalUsage(1, 1, 0, 0, 0, UsageClass.AUTHORITATIVE);
    assertThatThrownBy(() -> new CanonicalResponse("hi", List.of(), FinishReason.STOP, usage, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("providerMeta");
    final var response =
        new CanonicalResponse("hi", List.of(), FinishReason.STOP, usage, ProviderMeta.empty());
    assertThat(response.providerMeta().isEmpty()).isTrue();
  }

  @Test
  void providerMetaIsOpaqueImmutableWithValueEquality() {
    // H1: opaque bytes are cloned on write and on read (no exposure), with value equality/hashCode.
    final byte[] raw = {1, 2, 3};
    final ProviderMeta meta = ProviderMeta.of(raw);
    raw[0] = 9;
    assertThat(meta.bytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    meta.bytes()[0] = 7;
    assertThat(meta.bytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
    assertThat(meta).isEqualTo(ProviderMeta.of(new byte[] {1, 2, 3}));
    assertThat(meta.hashCode()).isEqualTo(ProviderMeta.of(new byte[] {1, 2, 3}).hashCode());
    assertThat(ProviderMeta.empty().isEmpty()).isTrue();
    assertThatThrownBy(() -> ProviderMeta.of(null)).isInstanceOf(NullPointerException.class);
  }

  private static boolean hasPublicSetter(final Class<?> type) {
    for (final var method : type.getMethods()) {
      if (method.getName().startsWith("set") && method.getParameterCount() == 1) {
        return true;
      }
    }
    return false;
  }
}
