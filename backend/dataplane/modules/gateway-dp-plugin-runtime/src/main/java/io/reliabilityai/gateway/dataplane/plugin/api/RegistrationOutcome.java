package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Whether a plugin was bound, and if not, why (Doc 28 §9, §13, §STC).
 *
 * <p>Refusal is a value, not an exception. Binding a set of plugins at startup means most refusals
 * are expected and individually survivable — one bad manifest should not abort a rollout — and a
 * caller that has to catch per plugin will eventually catch too broadly.
 */
public sealed interface RegistrationOutcome
    permits RegistrationOutcome.Bound, RegistrationOutcome.Refused {

  /**
   * Whether the plugin was bound.
   *
   * @return true for {@link Bound}
   */
  boolean bound();

  /**
   * The plugin was verified and bound in {@link PluginState#REGISTERED}.
   *
   * @param descriptor the resulting descriptor
   */
  record Bound(PluginDescriptor descriptor) implements RegistrationOutcome {
    /** Compact constructor validating the outcome. */
    public Bound {
      Preconditions.requireNonNull(descriptor, "descriptor");
    }

    @Override
    public boolean bound() {
      return true;
    }
  }

  /**
   * The plugin was refused. The reason is a bounded, content-free code.
   *
   * @param reason why the plugin was refused
   */
  record Refused(RefusalReason reason) implements RegistrationOutcome {
    /** Compact constructor validating the outcome. */
    public Refused {
      Preconditions.requireNonNull(reason, "reason");
    }

    @Override
    public boolean bound() {
      return false;
    }
  }

  /** Why a plugin was refused (Doc 28 EPC-7, STC-5, §13, ISO-1). */
  enum RefusalReason {
    /** Signature, digest, provenance or signer failed verification (Doc 28 §STC). */
    UNTRUSTED_SNAPSHOT,
    /** The manifest requests a capability Doc 28 forbids (Doc 28 §31.1, EPC-8, PRT-D8). */
    FORBIDDEN_CAPABILITY,
    /** The manifest requests a capability the runtime does not know how to enforce. */
    UNKNOWN_CAPABILITY,
    /** The manifest declares a capability it holds no matching permission for. */
    UNGRANTED_CAPABILITY,
    /** No substrate exists for the declared plugin type (Doc 28 REC-5 fail-closed). */
    UNSUPPORTED_TYPE,
    /** Third-party code declared a substrate that would run it in the host JVM (Doc 28 ISO-1). */
    ISOLATION_INSUFFICIENT,
    /** The instance's manifest disagrees with the signed manifest. */
    MANIFEST_MISMATCH,
    /** The plugin implements no capability interface, so it could never be invoked. */
    NO_CAPABILITY_INTERFACE,
    /** A declared dependency is absent or its version is incompatible (Doc 28 §14). */
    DEPENDENCY_UNSATISFIED,
    /** Binding this plugin would create a dependency cycle (Doc 28 §14). */
    DEPENDENCY_CYCLE,
    /** A plugin with this id is already bound; unregister or reload instead. */
    ALREADY_REGISTERED,
    /** The registry is at capacity. */
    CAPACITY_EXCEEDED
  }
}
