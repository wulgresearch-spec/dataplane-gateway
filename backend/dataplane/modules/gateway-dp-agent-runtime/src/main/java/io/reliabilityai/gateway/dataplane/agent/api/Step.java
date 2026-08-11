package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * One node of a plan (AD-025 §26.2).
 *
 * <p>A sealed hierarchy rather than a record with a kind field and a bag of optional properties:
 * the executor's whole surface is an exhaustive switch over these five, so the compiler — not a
 * code review — is what guarantees no step kind goes unhandled.
 *
 * <p>Every variant is an immutable value. A plan is data (AD-025 §22), which is what lets
 * governance authorize it before it runs, lets a run pin the exact version it started with, and
 * puts "what was this agent instructed to do" in the audit trail rather than in a repository at an
 * unknown commit.
 *
 * <p><b>No variant names a provider, a model, a plugin or a tool implementation</b> (AD-025 PLC-9).
 * Steps declare capabilities; resolution belongs to the layers that own it.
 */
public sealed interface Step
    permits Step.Pipeline, Step.Plugin, Step.Wait, Step.Condition, Step.Branch {

  /**
   * Returns the step's name, unique within its plan.
   *
   * @return the plan-local name, used by branch targets and input references
   */
  String name();

  /**
   * Returns which kind of step this is.
   *
   * @return the kind
   */
  StepKind kind();

  /**
   * Returns the capabilities this step needs granted to it.
   *
   * @return the declared capability set, possibly empty, in deterministic order
   */
  Set<String> requiredCapabilities();

  /**
   * Reports whether re-executing this step is safe.
   *
   * <p>Undeclared means <b>not</b> idempotent (AD-025 PLC-5). The supervisor refuses to auto-retry
   * a non-idempotent step, because a duplicated irreversible action is a customer incident while a
   * non-retried transient failure is an inconvenience.
   *
   * @return true when the step may be re-executed without external consequence
   */
  boolean idempotent();

  /**
   * Returns the spend this step may be allotted.
   *
   * @return the step's cost ceiling in micros, zero for steps that spend nothing
   */
  long budgetMicros();

  /** Sorts a declared capability set so plan digests are stable across nodes. */
  private static java.util.SortedSet<String> freeze(
      final Set<String> capabilities, final String field) {
    Preconditions.requireNonNull(capabilities, field);
    return new TreeSet<>(capabilities);
  }

  /**
   * One model invocation: one tool session, hence at least one complete pipeline execution.
   *
   * @param name the plan-local step name
   * @param instruction the static instruction text this step contributes
   * @param inputRefs names of earlier steps whose recorded results feed this one; the only way a
   *     prior result — including a {@link ToolResultArtifact} — reaches a model
   * @param requiredCapabilities the capabilities the session is opened with
   * @param maxOutputTokens the declared output bound handed to governance
   * @param budgetMicros the step's cost ceiling
   * @param deadline the session deadline
   * @param idempotent whether the step may be re-executed
   */
  record Pipeline(
      String name,
      String instruction,
      java.util.List<String> inputRefs,
      Set<String> requiredCapabilities,
      long maxOutputTokens,
      long budgetMicros,
      Duration deadline,
      boolean idempotent)
      implements Step {

    /**
     * Validates the step.
     *
     * @param name the plan-local name
     * @param instruction the instruction text
     * @param inputRefs the referenced earlier steps
     * @param requiredCapabilities the declared capabilities
     * @param maxOutputTokens the output bound
     * @param budgetMicros the cost ceiling
     * @param deadline the session deadline
     * @param idempotent the idempotence declaration
     */
    public Pipeline {
      Preconditions.requireNonBlank(name, "name");
      Preconditions.requireNonNull(instruction, "instruction");
      inputRefs = inputRefs == null ? List.of() : List.copyOf(inputRefs);
      // The unmodifiable wrapper is applied here, not inside freeze(): SpotBugs judges exposure
      // one frame at a time and cannot see a copy made further down.
      requiredCapabilities =
          java.util.Collections.unmodifiableSortedSet(
              freeze(requiredCapabilities, "requiredCapabilities"));
      Preconditions.requireNonNegative(maxOutputTokens, "maxOutputTokens");
      Preconditions.requireNonNegative(budgetMicros, "budgetMicros");
      Preconditions.requireNonNull(deadline, "deadline");
      if (deadline.isZero() || deadline.isNegative()) {
        throw new IllegalArgumentException("deadline must be positive, was " + deadline);
      }
    }

    @Override
    public StepKind kind() {
      return StepKind.PIPELINE;
    }
  }

  /**
   * One tool invocation through the Plugin Runtime, producing an artifact.
   *
   * @param name the plan-local step name
   * @param capability the capability to exercise; never a plugin id (AD-025 §41)
   * @param arguments the opaque argument payload handed to the tool
   * @param inputRefs names of earlier steps whose recorded results feed the arguments
   * @param budgetMicros the step's cost ceiling
   * @param deadline the tool deadline
   * @param idempotent whether the tool may be invoked twice without external consequence
   */
  record Plugin(
      String name,
      String capability,
      String arguments,
      java.util.List<String> inputRefs,
      long budgetMicros,
      Duration deadline,
      boolean idempotent)
      implements Step {

    /**
     * Validates the step.
     *
     * @param name the plan-local name
     * @param capability the capability to exercise
     * @param arguments the tool arguments
     * @param inputRefs the referenced earlier steps
     * @param budgetMicros the cost ceiling
     * @param deadline the tool deadline
     * @param idempotent the idempotence declaration
     */
    public Plugin {
      Preconditions.requireNonBlank(name, "name");
      Preconditions.requireNonBlank(capability, "capability");
      Preconditions.requireNonNull(arguments, "arguments");
      inputRefs = inputRefs == null ? List.of() : List.copyOf(inputRefs);
      Preconditions.requireNonNegative(budgetMicros, "budgetMicros");
      Preconditions.requireNonNull(deadline, "deadline");
      if (deadline.isZero() || deadline.isNegative()) {
        throw new IllegalArgumentException("deadline must be positive, was " + deadline);
      }
    }

    @Override
    public StepKind kind() {
      return StepKind.PLUGIN;
    }

    @Override
    public Set<String> requiredCapabilities() {
      return Set.of(capability);
    }
  }

  /**
   * A durable timer. Parks the run with no resources held.
   *
   * <p>Not a sleep. AD-025 §45.2 forbids the interpreter from blocking on wall-clock time; the step
   * records a wake instant and the run leaves memory entirely until a sweep or a claim finds it
   * due.
   *
   * @param name the plan-local step name
   * @param duration how long to park
   */
  record Wait(String name, Duration duration) implements Step {

    /**
     * Validates the step.
     *
     * @param name the plan-local name
     * @param duration the park duration
     */
    public Wait {
      Preconditions.requireNonBlank(name, "name");
      Preconditions.requireNonNull(duration, "duration");
      if (duration.isNegative()) {
        throw new IllegalArgumentException("duration must be non-negative, was " + duration);
      }
    }

    @Override
    public StepKind kind() {
      return StepKind.WAIT;
    }

    @Override
    public Set<String> requiredCapabilities() {
      return Set.of();
    }

    @Override
    public boolean idempotent() {
      return true;
    }

    @Override
    public long budgetMicros() {
      return 0L;
    }
  }

  /**
   * Evaluates a declared predicate over a recorded result and records the boolean.
   *
   * @param name the plan-local step name
   * @param subjectRef the earlier step whose recorded value the predicate is about
   * @param operator the predicate
   * @param operand the literal to compare against
   */
  record Condition(String name, String subjectRef, ConditionOperator operator, String operand)
      implements Step {

    /**
     * Validates the step.
     *
     * @param name the plan-local name
     * @param subjectRef the subject step name
     * @param operator the predicate
     * @param operand the comparison literal
     */
    public Condition {
      Preconditions.requireNonBlank(name, "name");
      Preconditions.requireNonBlank(subjectRef, "subjectRef");
      Preconditions.requireNonNull(operator, "operator");
      Preconditions.requireNonNull(operand, "operand");
    }

    @Override
    public StepKind kind() {
      return StepKind.CONDITION;
    }

    @Override
    public Set<String> requiredCapabilities() {
      return Set.of();
    }

    @Override
    public boolean idempotent() {
      return true;
    }

    @Override
    public long budgetMicros() {
      return 0L;
    }
  }

  /**
   * Evaluates a declared predicate and selects the next step by name.
   *
   * <p>Both targets are mandatory. An optional else-target would mean "fall through", and a plan
   * whose control flow depends on where a branch happens to sit in the step list is a plan whose
   * execution graph cannot be checked for termination by reading it.
   *
   * @param name the plan-local step name
   * @param subjectRef the earlier step whose recorded value the predicate is about
   * @param operator the predicate
   * @param operand the literal to compare against
   * @param whenTrue the step name to jump to when the predicate holds
   * @param whenFalse the step name to jump to when it does not
   */
  record Branch(
      String name,
      String subjectRef,
      ConditionOperator operator,
      String operand,
      String whenTrue,
      String whenFalse)
      implements Step {

    /**
     * Validates the step.
     *
     * @param name the plan-local name
     * @param subjectRef the subject step name
     * @param operator the predicate
     * @param operand the comparison literal
     * @param whenTrue the true target
     * @param whenFalse the false target
     */
    public Branch {
      Preconditions.requireNonBlank(name, "name");
      Preconditions.requireNonBlank(subjectRef, "subjectRef");
      Preconditions.requireNonNull(operator, "operator");
      Preconditions.requireNonNull(operand, "operand");
      Preconditions.requireNonBlank(whenTrue, "whenTrue");
      Preconditions.requireNonBlank(whenFalse, "whenFalse");
    }

    @Override
    public StepKind kind() {
      return StepKind.BRANCH;
    }

    @Override
    public Set<String> requiredCapabilities() {
      return Set.of();
    }

    @Override
    public boolean idempotent() {
      return true;
    }

    @Override
    public long budgetMicros() {
      return 0L;
    }
  }
}
