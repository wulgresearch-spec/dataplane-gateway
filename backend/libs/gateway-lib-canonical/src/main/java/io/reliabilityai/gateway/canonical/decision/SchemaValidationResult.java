package io.reliabilityai.gateway.canonical.decision;

import java.util.List;

/**
 * SchemaLock's validation verdict for a response/tool-call (C3, Doc 17, Doc 33 §10.4). SchemaLock
 * decides; a validation plugin only contributes a signal (Doc 32 §PEB). Immutable; violations
 * copied.
 *
 * @param valid whether the payload validates
 * @param violations content-free violation descriptors (empty when valid)
 */
public record SchemaValidationResult(boolean valid, List<String> violations) {

  /** Compact constructor defensively copying violations. */
  public SchemaValidationResult {
    // Inlined copy, not Preconditions.immutableList — see that method's javadoc (EI_EXPOSE_REP).
    violations = violations == null ? List.of() : List.copyOf(violations);
  }

  /**
   * Returns a passing result with no violations.
   *
   * @return a valid result
   */
  public static SchemaValidationResult passed() {
    return new SchemaValidationResult(true, List.of());
  }
}
