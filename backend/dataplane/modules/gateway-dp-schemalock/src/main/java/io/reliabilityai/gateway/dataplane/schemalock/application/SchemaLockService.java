package io.reliabilityai.gateway.dataplane.schemalock.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.api.CanonicalOutput;
import io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.CorrectnessOutcomeSink;
import io.reliabilityai.gateway.dataplane.schemalock.api.OutputSchema;
import io.reliabilityai.gateway.dataplane.schemalock.api.ProviderGenerationPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.ProviderGenerationPort.GenerationCommand;
import io.reliabilityai.gateway.dataplane.schemalock.api.ProviderGenerationPort.GenerationOutcome;
import io.reliabilityai.gateway.dataplane.schemalock.api.RetryDecisionPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaCompilationException;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPolicy;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaLockPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.SchemaValidatorPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort;
import io.reliabilityai.gateway.dataplane.schemalock.api.StructuredOutputRequest;
import io.reliabilityai.gateway.dataplane.schemalock.api.StructuredStreamSession;
import io.reliabilityai.gateway.dataplane.schemalock.domain.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation;
import io.reliabilityai.gateway.dataplane.schemalock.domain.Strategy;
import io.reliabilityai.gateway.dataplane.schemalock.domain.StrategySelector;
import io.reliabilityai.gateway.dataplane.schemalock.domain.ValidationVerdict;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The SchemaLock engine (Doc 17 §7/§8) — the non-bypassable, fail-closed structured-output
 * correctness pipeline (SL-INV, BRULE-1, AD-018). It compiles/caches schemas (content-hash LRU),
 * selects the safest strategy deterministically, generates via the neutral provider seam, applies
 * only value-preserving NR-1 repair, runs the authoritative completion validation, and resolves to
 * either a completion- validated {@link CanonicalOutput} or an explicit surfaced non-conformance —
 * <b>never a silently incorrect value</b>. There is no code path from generation to a returned
 * value that skips validation (§7, SL-A3).
 *
 * <p>Stateless per request (AD-021) apart from the shared immutable compiled-schema cache;
 * deterministic given the port results (Doc 17 §28). Bounded by the attempt cap, output-size cap,
 * and the Retry Engine's shared budget (§24.1) — never loops unbounded. Any validator adapter error
 * fails closed and surfaces (never delivers, §48).
 */
public final class SchemaLockService implements SchemaLockPort {

  private final SchemaValidatorPort validator;
  private final ProviderGenerationPort generation;
  private final RetryDecisionPort retry;
  private final CorrectnessOutcomeSink outcomeSink;
  private final SchemaLockPolicy policy;
  private final ClockPort clock;
  private final CompiledSchemaCache cache;

  /**
   * Creates the engine against its injected ports (AD-002).
   *
   * @param validator the replaceable JSON Schema validator seam (Doc 17 §9)
   * @param generation the neutral provider generation seam (Doc 17 §42)
   * @param retry the retry-coordination seam (Doc 17 §24)
   * @param outcomeSink the content-free correctness-outcome sink (Doc 17 §38); use {@link
   *     CorrectnessOutcomeSink#NO_OP} to omit
   * @param policy the injected budgets/limits (Doc 17 §24.1/§31)
   * @param clock the deterministic time seam for the total-latency budget (Doc 17 §24.1/§28)
   * @param cacheCapacity the compiled-schema LRU capacity (Doc 17 §10)
   */
  public SchemaLockService(
      final SchemaValidatorPort validator,
      final ProviderGenerationPort generation,
      final RetryDecisionPort retry,
      final CorrectnessOutcomeSink outcomeSink,
      final SchemaLockPolicy policy,
      final ClockPort clock,
      final int cacheCapacity) {
    this.validator = Preconditions.requireNonNull(validator, "validator");
    this.generation = Preconditions.requireNonNull(generation, "generation");
    this.retry = Preconditions.requireNonNull(retry, "retry");
    this.outcomeSink = Preconditions.requireNonNull(outcomeSink, "outcomeSink");
    this.policy = Preconditions.requireNonNull(policy, "policy");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.cache = new CompiledSchemaCache(cacheCapacity);
  }

  @Override
  public CompiledSchema compile(final OutputSchema schema) throws SchemaCompilationException {
    Preconditions.requireNonNull(schema, "schema");
    final Optional<CompiledSchema> cached = cache.get(schema.schemaId());
    if (cached.isPresent()) {
      return cached.get();
    }
    final CompiledSchema compiled =
        validator.compile(schema); // throws SCHEMA_INVALID (caller error)
    cache.put(compiled);
    return compiled;
  }

  @Override
  public CanonicalOutput executeStructured(
      final StructuredOutputRequest request, final CompiledSchema schema) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(schema, "schema");
    final CapabilityDescriptor capability =
        CapabilityDescriptor.validatedOrLeastCapable(request.capability());
    final Optional<Strategy> selected =
        StrategySelector.select(capability, schema.complexity(), false);
    if (selected.isEmpty()) {
      return surface(
          schema,
          Strategy.PROMPT_CONSTRAINED,
          FailureClass.CAPABILITY_UNSUPPORTED,
          List.of(),
          0,
          false);
    }
    final Strategy strategy = selected.get();

    // Hard total-latency budget for the whole structured request across all attempts (Doc 17
    // §24.1):
    // on projected breach, stop and surface rather than start another attempt — a bounded
    // worst-case
    // that never trades correctness for completion (SL-A14). Overflow-guarded so a pathological
    // policy
    // can never throw out of the fail-closed engine.
    final java.time.Instant latencyDeadline = latencyDeadline(clock.now());

    List<SchemaViolation> feedback = List.of();
    FailureClass lastClass = FailureClass.NON_CONFORMANT;
    for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
      if (!clock.now().isBefore(latencyDeadline)) {
        // Latency budget exhausted: cancel any in-flight provider call to stop token spend and
        // surface
        // TIMEOUT — never relax validation or deliver an unvalidated value (Doc 17 §24.1 cardinal
        // rule).
        safeCancelGeneration();
        return surface(schema, strategy, FailureClass.TIMEOUT, feedback, attempt - 1, false);
      }
      final GenerationOutcome outcome;
      try {
        outcome = generation.generate(new GenerationCommand(request, schema, strategy, feedback));
      } catch (final RuntimeException e) {
        return surface(schema, strategy, FailureClass.PROVIDER_ERROR, List.of(), attempt, false);
      }

      if (outcome instanceof GenerationOutcome.Failed failed) {
        lastClass = failed.failureClass();
        if (canRetry(lastClass, attempt)) {
          feedback = List.of();
          continue;
        }
        return surface(schema, strategy, lastClass, List.of(), attempt, false);
      }

      final String produced = ((GenerationOutcome.Produced) outcome).outputText();
      if (produced.getBytes(StandardCharsets.UTF_8).length > policy.maxOutputBytes()) {
        return surface(schema, strategy, FailureClass.RESOURCE_EXCEEDED, List.of(), attempt, false);
      }
      final String normalized =
          io.reliabilityai.gateway.dataplane.schemalock.domain.ConservativeRepair.stripEnvelope(
              produced);
      final boolean repairApplied = !normalized.equals(produced);

      final ValidationVerdict verdict;
      try {
        verdict = validator.validate(schema, normalized);
      } catch (final RuntimeException e) {
        // Validator adapter error ⇒ fail closed, never deliver (Doc 17 §48).
        return surface(
            schema, strategy, FailureClass.PROVIDER_ERROR, List.of(), attempt, repairApplied);
      }

      if (verdict.conformant()) {
        safeOutcome(schema.schemaId(), true, null, strategy, attempt);
        return CanonicalOutput.conformant(normalized, schema, strategy, attempt, repairApplied);
      }
      lastClass = FailureClass.NON_CONFORMANT;
      feedback = boundedViolations(verdict.violations());
      if (!canRetry(FailureClass.NON_CONFORMANT, attempt)) {
        return surface(
            schema, strategy, FailureClass.NON_CONFORMANT, feedback, attempt, repairApplied);
      }
    }
    // Attempts exhausted ⇒ surface, never deliver (Doc 17 §24.1 cardinal rule).
    return surface(schema, strategy, lastClass, feedback, policy.maxAttempts(), false);
  }

  @Override
  public StructuredStreamSession openStream(
      final StructuredOutputRequest request,
      final CompiledSchema schema,
      final StreamSourcePort source) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(schema, "schema");
    Preconditions.requireNonNull(source, "source");
    final CapabilityDescriptor capability =
        CapabilityDescriptor.validatedOrLeastCapable(request.capability());
    final Optional<Strategy> selected =
        StrategySelector.select(capability, schema.complexity(), true);
    final Strategy strategy = selected.orElse(Strategy.PROMPT_CONSTRAINED);
    final boolean capable = selected.isPresent();
    return new StructuredStreamSessionImpl(
        validator, outcomeSink, policy, source, schema, strategy, capable);
  }

  private java.time.Instant latencyDeadline(final java.time.Instant start) {
    try {
      return start.plus(policy.perAttemptTimeout().multipliedBy(policy.maxAttempts()));
    } catch (final ArithmeticException | java.time.DateTimeException overflow) {
      // A pathological (enormous) budget can never throw out of the engine; the attempt cap still
      // bounds.
      return java.time.Instant.MAX;
    }
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

  private void safeCancelGeneration() {
    try {
      generation.cancel(); // cooperative provider cancel on budget exhaustion (Doc 17 §24.1)
    } catch (final RuntimeException ignored) {
      // cancellation is best-effort; never alters the surfaced outcome
    }
  }

  private boolean canRetry(final FailureClass failureClass, final int attempt) {
    return failureClass.recoverable()
        && attempt < policy.maxAttempts()
        && retry.shouldRetry(attempt + 1, failureClass);
  }

  private List<SchemaViolation> boundedViolations(final List<SchemaViolation> violations) {
    if (violations.size() <= policy.maxViolations()) {
      return violations;
    }
    return List.copyOf(violations.subList(0, policy.maxViolations()));
  }

  private CanonicalOutput surface(
      final CompiledSchema schema,
      final Strategy strategy,
      final FailureClass classification,
      final List<SchemaViolation> violations,
      final int attempts,
      final boolean repairApplied) {
    safeOutcome(schema.schemaId(), false, classification, strategy, attempts);
    return CanonicalOutput.surfaced(
        classification, violations, schema, strategy, attempts, repairApplied);
  }
}
