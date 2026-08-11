package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The opaque identity of a registered provider.
 *
 * <p>Opaque is the operative word. This value exists so operators can read logs, scope credentials
 * and name a provider in configuration — <b>not</b> so gateway code can branch on it. Nothing
 * outside a provider module compares this to a literal; the router dispatches on route references
 * and every behavioural question is asked of {@link ProviderDescriptor} capabilities instead
 * (AD-007, PA-A4). A test asserts that no provider name appears outside the provider modules, which
 * is what keeps that from eroding.
 *
 * <p>A value type rather than a bare string so that "the provider" and "the route" cannot be
 * transposed at a call site — they are both strings and mixing them up would silently dispatch to
 * the wrong adapter.
 *
 * @param value the operator-facing provider identifier
 */
public record ProviderId(String value) {

  /** Validates the identifier. */
  public ProviderId {
    Preconditions.requireNonBlank(value, "value");
  }

  /**
   * Creates a provider id.
   *
   * @param value the identifier
   * @return the provider id
   */
  public static ProviderId of(final String value) {
    return new ProviderId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
