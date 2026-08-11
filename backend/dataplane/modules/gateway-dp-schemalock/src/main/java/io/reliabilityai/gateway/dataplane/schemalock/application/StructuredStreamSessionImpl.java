package io.reliabilityai.gateway.dataplane.schemalock.application;

import io.reliabilityai.gateway.dataplane.schemalock.api.CanonicalOutput;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.CorrectnessOutcomeSink;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPolicy;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaValidatorPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort.StreamItem;
import io.reliabilityai.gateway.dataplane.schemalock.api.StructuredStreamSession;
import io.reliabilityai.gateway.dataplane.schemalock.domain.ConservativeRepair;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import io.reliabilityai.gateway.dataplane.schemalock.domain.Strategy;
import io.reliabilityai.gateway.dataplane.schemalock.domain.ValidationVerdict;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The streaming structured-output session (Doc 17 §8/§18/§43.1). It assembles the logical object
 * from StreamGuard's well-formed deltas (RB-5), runs advisory incremental impossibility checks to
 * fail fast (§19/§19.1 — never a conformance decision), and issues the <b>authoritative</b>
 * completion validation only on a clean transport completion (§21). The terminal success is gated
 * on that verdict (§8/§30, SL-A10): a partial is never labeled conformant, and any transport
 * failure or non-conformance surfaces (SL-INV). A single pass; a retry restarts a fresh stream at
 * composition (never merges partials, §24.1).
 */
final class StructuredStreamSessionImpl implements StructuredStreamSession {

  private final SchemaValidatorPort validator;
  private final CorrectnessOutcomeSink outcomeSink;
  private final SchemaLockPolicy policy;
  private final StreamSourcePort source;
  private final CompiledSchema schema;
  private final Strategy strategy;
  private final boolean capable;

  StructuredStreamSessionImpl(
      final SchemaValidatorPort validator,
      final CorrectnessOutcomeSink outcomeSink,
      final SchemaLockPolicy policy,
      final StreamSourcePort source,
      final CompiledSchema schema,
      final Strategy strategy,
      final boolean capable) {
    this.validator = validator;
    this.outcomeSink = outcomeSink;
    this.policy = policy;
    this.source = source;
    this.schema = schema;
    this.strategy = strategy;
    this.capable = capable;
  }

  @Override
  public CanonicalOutput awaitCompletion() {
    if (!capable) {
      return surface(
          FailureClass.CAPABILITY_UNSUPPORTED, List.of(), false); // streaming unsupported
    }
    final StringBuilder assembled = new StringBuilder();
    while (true) {
      final StreamItem item;
      try {
        item = source.next();
      } catch (final RuntimeException sourceError) {
        // An unexpected StreamGuard source fault must fail closed to a surfaced outcome, never
        // throw
        // out of awaitCompletion() (Doc 17 §48 / SL-INV) — consistent with the validator-error
        // paths.
        return surface(FailureClass.PROVIDER_ERROR, List.of(), false);
      }
      if (item instanceof StreamItem.Delta delta) {
        assembled.append(delta.fragment());
        if (assembled.toString().getBytes(StandardCharsets.UTF_8).length
            > policy.maxOutputBytes()) {
          return surface(FailureClass.RESOURCE_EXCEEDED, List.of(), false); // bounded (Doc 17 §31)
        }
        try {
          if (validator.isStructurallyImpossible(schema, assembled.toString())) {
            return surface(
                FailureClass.NON_CONFORMANT, List.of(), false); // abort before terminal (§19)
          }
        } catch (final RuntimeException e) {
          return surface(
              FailureClass.PROVIDER_ERROR, List.of(), false); // validator error ⇒ fail closed
        }
      } else if (item instanceof StreamItem.TransportFailed failed) {
        return surface(
            failed.failureClass(), List.of(), false); // StreamGuard transport failure (§43.1)
      } else {
        // TransportComplete: authoritative completion validation, terminal-gated (Doc 17 §21/§30).
        final String normalized = ConservativeRepair.stripEnvelope(assembled.toString());
        final boolean repairApplied = !normalized.equals(assembled.toString());
        final ValidationVerdict verdict;
        try {
          verdict = validator.validate(schema, normalized);
        } catch (final RuntimeException e) {
          return surface(FailureClass.PROVIDER_ERROR, List.of(), repairApplied);
        }
        if (verdict.conformant()) {
          safeOutcome(schema.schemaId(), true, null, strategy, 1);
          return CanonicalOutput.conformant(normalized, schema, strategy, 1, repairApplied);
        }
        return surface(
            FailureClass.NON_CONFORMANT, boundedViolations(verdict.violations()), repairApplied);
      }
    }
  }

  private List<io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation>
      boundedViolations(
          final List<io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation>
              violations) {
    if (violations.size() <= policy.maxViolations()) {
      return violations;
    }
    return List.copyOf(violations.subList(0, policy.maxViolations()));
  }

  private CanonicalOutput surface(
      final FailureClass classification,
      final List<io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation> violations,
      final boolean repairApplied) {
    safeOutcome(schema.schemaId(), false, classification, strategy, 1);
    return CanonicalOutput.surfaced(classification, violations, schema, strategy, 1, repairApplied);
  }

  private void safeOutcome(
      final SchemaId schemaId,
      final boolean conformant,
      final FailureClass classification,
      final Strategy strategy,
      final int attempts) {
    try {
      outcomeSink.onOutcome(schemaId, conformant, classification, strategy, attempts);
    } catch (final RuntimeException ignored) {
      // content-free outcome sink is best-effort — never affects/fails the correctness decision
      // (§38)
    }
  }
}
