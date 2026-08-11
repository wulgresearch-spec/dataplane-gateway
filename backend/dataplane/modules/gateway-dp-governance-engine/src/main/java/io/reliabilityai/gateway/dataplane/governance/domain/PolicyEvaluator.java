package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyViolation;
import io.reliabilityai.gateway.dataplane.governance.api.ResolvedPolicyContext;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * The decision core: a pure function from {@code (request, effective policy, consumption, now)} to
 * a verdict (Doc 21 GV-D12).
 *
 * <p><b>Pure</b> in the sense that matters for governance: no I/O, no wall clock, no randomness, no
 * mutable state shared between calls. Two evaluations with identical inputs produce identical
 * verdicts, identical violations and identical explanations, which is what makes it possible to
 * answer "why was this request refused on the 3rd of March" months later, with the same code, and
 * get the same answer.
 *
 * <p><b>The loop is the algorithm.</b> One pass over a fixed, closed set of policy types, in a
 * fixed precedence order, reading each type's merged statement from an array. There is no rule
 * matching, no predicate tree, no scanning of a policy list, and no recursion into the hierarchy —
 * the hierarchy was folded away at snapshot install time. Cost is bounded by the number of policy
 * types, not by how many policies an organization has written, so a customer with a thousand
 * documents is evaluated as fast as one with three.
 *
 * <p><b>Deny-overrides, so order is a courtesy not a dependency.</b> The verdict is the maximum
 * severity across every violated statement, and maximum is commutative — so the precedence order
 * changes only which violation is <em>reported</em> and how early the loop can stop, never what is
 * decided. The one shortcut taken is breaking out on the first mandatory violation, which is safe
 * precisely because nothing later in the loop could produce a more severe outcome than a refusal.
 *
 * <p><b>Every uncertainty refuses.</b> An unknown consumption counter, a reading past its freshness
 * tolerance, a ceiling that cannot be checked — all produce a violation rather than a pass.
 * Admitting against a cap the engine cannot see is indistinguishable, from the outside, from having
 * no cap (Doc 21 §42, GC-5).
 */
public final class PolicyEvaluator {

  private static final PolicyType[] TYPES = PolicyType.values();

  private final Duration usageStalenessTolerance;
  private final Duration contextTtl;

  /**
   * Creates the evaluator.
   *
   * @param usageStalenessTolerance how old a consumption reading may be and still be enforced
   *     against; beyond this a mandatory ceiling refuses rather than admitting unbounded
   *     consumption
   * @param contextTtl how long an admitted request's resolved constraints stay valid before the
   *     pipeline must re-govern (Doc 21 §36.1 RGC-4)
   */
  public PolicyEvaluator(final Duration usageStalenessTolerance, final Duration contextTtl) {
    this.usageStalenessTolerance =
        requirePositive(usageStalenessTolerance, "usageStalenessTolerance");
    this.contextTtl = requirePositive(contextTtl, "contextTtl");
  }

  private static Duration requirePositive(final Duration value, final String field) {
    Preconditions.requireNonNull(value, field);
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  /**
   * Evaluates one request against one effective policy.
   *
   * @param request the governance question
   * @param policy the merged policy governing the request's scope chain
   * @param usage the consumption lookup
   * @param now the injected-clock instant
   * @param ticker the injected monotonic ticker used to measure evaluation cost
   * @return the decision — never null, never thrown
   */
  public PolicyDecision evaluate(
      final PolicyRequest request,
      final EffectivePolicy policy,
      final UsageLookup usage,
      final Instant now,
      final TickerPort ticker) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(policy, "policy");
    Preconditions.requireNonNull(usage, "usage");
    Preconditions.requireNonNull(now, "now");
    Preconditions.requireNonNull(ticker, "ticker");

    final long start = ticker.nanos();
    final List<PolicyViolation> violations = new ArrayList<>(2);
    Verdict worst = Verdict.ALLOW;

    for (final PolicyType type : TYPES) {
      final ResolvedRule rule = policy.ruleFor(type);
      if (rule == null) {
        continue;
      }
      final Outcome outcome = check(type, rule, request, usage, now);
      if (outcome == Outcome.SATISFIED) {
        continue;
      }
      final DenialReason reason =
          outcome == Outcome.UNENFORCEABLE ? DenialReason.POLICY_UNAVAILABLE : type.denialReason();
      final PolicyViolation violation =
          new PolicyViolation(
              type, rule.source().scope(), rule.ruleId(), rule.enforcement(), reason);
      violations.add(violation);
      worst = worst.worst(violation.verdict());
      if (worst == Verdict.DENY) {
        break;
      }
    }

    final long latency = Math.max(0L, ticker.nanos() - start);
    final ResolvedPolicyContext context =
        worst == Verdict.DENY
            ? ResolvedPolicyContext.denied(policy.version(), now)
            : resolve(request, policy, now);
    return PolicyDecision.of(violations, policy.version(), latency, context);
  }

  /** What one statement concluded about one request. */
  private enum Outcome {
    /** The request is within the statement. */
    SATISFIED,
    /** The request breaches the statement. */
    VIOLATED,
    /**
     * The statement could not be checked — the consumption it depends on is unknown or too old.
     * Treated as a breach so that an unverifiable cap refuses rather than waves the request
     * through.
     */
    UNENFORCEABLE
  }

  private Outcome check(
      final PolicyType type,
      final ResolvedRule rule,
      final PolicyRequest request,
      final UsageLookup usage,
      final Instant now) {
    return switch (type) {
      case EMERGENCY_KILL_SWITCH, TENANT_SUSPENSION -> breachIf(flag(rule));
      case MAINTENANCE_WINDOW -> breachIf(windows(rule).covers(now));
      case COMPLIANCE_MODE ->
          breachIf(!containsAll(values(rule), request.requiredComplianceRegimes()));
      case PII_RESTRICTION -> breachIf(flag(rule) && request.containsPii());
      case REGION_RESTRICTION -> breachIf(!values(rule).contains(request.region().value()));

      // An unknown candidate set is not an empty one: a provider list that cannot be checked
      // refuses.
      case PROVIDER_ALLOW_LIST ->
          request
              .candidateProviders()
              .map(asked -> breachIf(excludesEvery(values(rule), asked)))
              .orElse(Outcome.UNENFORCEABLE);
      case PROVIDER_DENY_LIST ->
          request
              .candidateProviders()
              .map(asked -> breachIf(deniesEvery(values(rule), asked)))
              .orElse(Outcome.UNENFORCEABLE);
      // An absent model means the request addresses none, so neither list has anything to compare
      // against. Skipping is the same treatment every unused attribute gets, not a special
      // exemption.
      case MODEL_ALLOW_LIST ->
          breachIf(request.model().map(id -> !values(rule).contains(id.value())).orElse(false));
      case MODEL_DENY_LIST ->
          breachIf(request.model().map(id -> values(rule).contains(id.value())).orElse(false));
      case TOOL_ALLOW_LIST -> breachIf(!containsAll(values(rule), request.tools()));
      case TOOL_DENY_LIST -> breachIf(containsAny(values(rule), request.tools()));

      case STREAMING_ALLOWED -> breachIf(request.streaming() && !flag(rule));
      case STREAMING_REQUIRED -> breachIf(flag(rule) && !request.streaming());
      case JSON_MODE_REQUIRED -> breachIf(flag(rule) && !request.jsonMode());
      case REASONING_ALLOWED -> breachIf(request.reasoning() && !flag(rule));
      case VISION_ALLOWED -> breachIf(request.vision() && !flag(rule));
      case IMAGE_GENERATION_ALLOWED -> breachIf(request.imageGeneration() && !flag(rule));
      case AUDIO_ALLOWED -> breachIf(request.audio() && !flag(rule));
      case EMBEDDING_ALLOWED -> breachIf(request.embedding() && !flag(rule));
      case FINE_TUNING_ALLOWED -> breachIf(request.fineTuning() && !flag(rule));
      case BATCH_ALLOWED -> breachIf(request.batch() && !flag(rule));

      // A ceiling compared against an uncounted quantity passes trivially, which is
      // indistinguishable
      // from having no ceiling. Unknown therefore refuses rather than waving the request through.
      case MAX_CONTEXT -> againstCeiling(request.contextTokens(), limit(rule));
      case MAX_OUTPUT_TOKENS -> againstCeiling(request.outputTokens(), limit(rule));
      case MAX_COST -> againstCeiling(request.projectedCostMicros(), limit(rule));

      case MAX_REQUESTS, MAX_RPM, MAX_TPM, CONCURRENCY_LIMIT, DAILY_BUDGET, MONTHLY_BUDGET ->
          checkConsumption(type, rule, request, usage, now);
    };
  }

  /**
   * Checks a ceiling that depends on what has already been consumed at the node the ceiling came
   * from.
   *
   * <p>Reading at the <em>binding</em> node rather than at the tenant is what makes a per-project
   * cap mean anything: a project's requests-per-minute ceiling measured against its organization's
   * counter would be exhausted by a busy sibling project that never touched it.
   */
  private Outcome checkConsumption(
      final PolicyType type,
      final ResolvedRule rule,
      final PolicyRequest request,
      final UsageLookup usage,
      final Instant now) {
    final OptionalLong increment = incrementFor(type, request);
    if (increment.isEmpty()) {
      // The request's own contribution is unknown, so no arithmetic against the cap is meaningful.
      return Outcome.UNENFORCEABLE;
    }
    final Optional<PolicyUsage> reading = usage.at(rule.source());
    if (reading.isEmpty()) {
      return Outcome.UNENFORCEABLE;
    }
    final PolicyUsage consumption = reading.orElseThrow();
    if (!consumption.isFreshAt(now, usageStalenessTolerance)) {
      return Outcome.UNENFORCEABLE;
    }
    final long ceiling = limit(rule);
    final long added = increment.getAsLong();
    final long consumed = consumption.consumedFor(type);
    // Rearranged to avoid overflowing when a ceiling sits near Long.MAX_VALUE; both terms are
    // non-negative by construction, so this is equivalent to consumed + added > ceiling.
    return breachIf(added > ceiling || consumed > ceiling - added);
  }

  /** What this one request would add to a counter, or empty when the request cannot say. */
  private static OptionalLong incrementFor(final PolicyType type, final PolicyRequest request) {
    return switch (type) {
      case MAX_TPM -> sum(request.contextTokens(), request.outputTokens());
      case DAILY_BUDGET, MONTHLY_BUDGET -> request.projectedCostMicros();
      default -> OptionalLong.of(1L);
    };
  }

  /**
   * A token-rate cap needs both halves of the request's token footprint; either missing is unknown.
   */
  private static OptionalLong sum(final OptionalLong left, final OptionalLong right) {
    return left.isPresent() && right.isPresent()
        ? OptionalLong.of(left.getAsLong() + right.getAsLong())
        : OptionalLong.empty();
  }

  /**
   * Compares a possibly-unknown quantity against a ceiling; unknown cannot be compared, so it
   * fails.
   */
  private static Outcome againstCeiling(final OptionalLong quantity, final long ceiling) {
    return quantity.isPresent() ? breachIf(quantity.getAsLong() > ceiling) : Outcome.UNENFORCEABLE;
  }

  /**
   * Builds the constraints an admitted request carries to the Router (Doc 21 §36.1).
   *
   * <p>Only <b>mandatory</b> statements contribute. A shadow rule exists to be measured, not to
   * have effect, so letting one narrow the Router's provider set would make shadow mode change
   * production routing — the exact thing shadow mode promises not to do. Advisory rules are
   * likewise recorded and surfaced, never enforced downstream.
   */
  private ResolvedPolicyContext resolve(
      final PolicyRequest request, final EffectivePolicy policy, final Instant now) {
    final List<String> providers =
        subtract(
            baseOrDefault(
                policy,
                PolicyType.PROVIDER_ALLOW_LIST,
                request.candidateProviders().orElse(List.of())),
            listOf(policy, PolicyType.PROVIDER_DENY_LIST));
    final List<String> tools =
        subtract(
            baseOrDefault(policy, PolicyType.TOOL_ALLOW_LIST, request.tools()),
            listOf(policy, PolicyType.TOOL_DENY_LIST));
    final List<String> models =
        subtract(
            listOf(policy, PolicyType.MODEL_ALLOW_LIST),
            listOf(policy, PolicyType.MODEL_DENY_LIST));

    return new ResolvedPolicyContext(
        listOf(policy, PolicyType.REGION_RESTRICTION),
        request.requiredComplianceRegimes(),
        providers,
        models,
        tools,
        limitOf(policy, PolicyType.MAX_CONTEXT),
        limitOf(policy, PolicyType.MAX_OUTPUT_TOKENS),
        limitOf(policy, PolicyType.MAX_COST),
        flagOf(policy, PolicyType.STREAMING_REQUIRED),
        flagOf(policy, PolicyType.JSON_MODE_REQUIRED),
        policy.version(),
        now.plus(contextTtl));
  }

  /** The merged statement for a type, but only when it is mandatory and therefore has effect. */
  private static ResolvedRule enforced(final EffectivePolicy policy, final PolicyType type) {
    final ResolvedRule rule = policy.ruleFor(type);
    return rule != null && rule.enforcement() == EnforcementLevel.MANDATORY ? rule : null;
  }

  private static List<String> listOf(final EffectivePolicy policy, final PolicyType type) {
    final ResolvedRule rule = enforced(policy, type);
    return rule == null ? List.of() : values(rule).values();
  }

  private static List<String> baseOrDefault(
      final EffectivePolicy policy, final PolicyType type, final List<String> fallback) {
    final ResolvedRule rule = enforced(policy, type);
    return rule == null ? fallback : values(rule).values();
  }

  private static long limitOf(final EffectivePolicy policy, final PolicyType type) {
    final ResolvedRule rule = enforced(policy, type);
    return rule == null ? ResolvedPolicyContext.UNBOUNDED : limit(rule);
  }

  private static boolean flagOf(final EffectivePolicy policy, final PolicyType type) {
    final ResolvedRule rule = enforced(policy, type);
    return rule != null && flag(rule);
  }

  private static List<String> subtract(final List<String> base, final List<String> removed) {
    if (removed.isEmpty() || base.isEmpty()) {
      return base;
    }
    final TreeSet<String> kept = new TreeSet<>(base);
    kept.removeAll(removed);
    return List.copyOf(kept);
  }

  private static Outcome breachIf(final boolean breached) {
    return breached ? Outcome.VIOLATED : Outcome.SATISFIED;
  }

  /**
   * True when nothing the request offered survives the allow-list; an empty ask cannot be excluded.
   */
  private static boolean excludesEvery(final PolicyValue.Values allowed, final List<String> asked) {
    if (asked.isEmpty()) {
      return false;
    }
    for (final String candidate : asked) {
      if (allowed.contains(candidate)) {
        return false;
      }
    }
    return true;
  }

  /**
   * True when the deny-list removes every option the request offered, leaving nothing to route to.
   */
  private static boolean deniesEvery(final PolicyValue.Values denied, final List<String> asked) {
    if (asked.isEmpty()) {
      return false;
    }
    for (final String candidate : asked) {
      if (!denied.contains(candidate)) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsAll(final PolicyValue.Values allowed, final List<String> asked) {
    for (final String candidate : asked) {
      if (!allowed.contains(candidate)) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsAny(final PolicyValue.Values denied, final List<String> asked) {
    for (final String candidate : asked) {
      if (denied.contains(candidate)) {
        return true;
      }
    }
    return false;
  }

  private static PolicyValue.Values values(final ResolvedRule rule) {
    return (PolicyValue.Values) rule.value();
  }

  private static boolean flag(final ResolvedRule rule) {
    return ((PolicyValue.Flag) rule.value()).value();
  }

  private static long limit(final ResolvedRule rule) {
    return ((PolicyValue.Limit) rule.value()).value();
  }

  private static PolicyValue.Windows windows(final ResolvedRule rule) {
    return (PolicyValue.Windows) rule.value();
  }
}
