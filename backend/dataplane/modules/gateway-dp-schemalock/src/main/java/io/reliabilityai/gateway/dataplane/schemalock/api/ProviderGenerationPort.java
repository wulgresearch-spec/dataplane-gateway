package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation;
import io.reliabilityai.gateway.dataplane.schemalock.domain.Strategy;
import java.util.List;

/**
 * The generation seam to the Provider Router (C1, Doc 17 §42, SL-A1). SchemaLock requests
 * generation under a chosen {@link Strategy} with structural violation feedback (Doc 17 §24); the
 * Router owns provider selection/failover/neutralization (AD-007) and returns neutral output text
 * or a neutral failure — <b>no provider SDK/type/field ever enters SchemaLock</b>. Supports
 * cooperative cancellation to stop token spend on budget exhaustion (Doc 17 §24.1).
 */
public interface ProviderGenerationPort {

  /**
   * Generates one attempt under the given strategy and feedback (Doc 17 §7 stage 5).
   *
   * @param command the generation command
   * @return the neutral generation outcome (produced text or a neutral failure class)
   */
  GenerationOutcome generate(GenerationCommand command);

  /** Cooperatively cancels the in-flight provider generation to stop token spend (Doc 17 §24.1). */
  void cancel();

  /**
   * A generation command (Doc 17 §7).
   *
   * @param request the structured-output request
   * @param schema the compiled schema
   * @param strategy the chosen strategy
   * @param feedback the structural violations from the prior attempt (empty on first attempt)
   */
  record GenerationCommand(
      StructuredOutputRequest request,
      CompiledSchema schema,
      Strategy strategy,
      List<SchemaViolation> feedback) {
    /** Compact constructor validating and copying feedback. */
    public GenerationCommand {
      Preconditions.requireNonNull(request, "request");
      Preconditions.requireNonNull(schema, "schema");
      Preconditions.requireNonNull(strategy, "strategy");
      feedback = feedback == null ? List.of() : List.copyOf(feedback);
    }
  }

  /**
   * A neutral generation outcome (Doc 17 §25). Sealed: produced output text, or a neutral failure.
   */
  sealed interface GenerationOutcome permits GenerationOutcome.Produced, GenerationOutcome.Failed {

    /**
     * The provider produced output text (unvalidated).
     *
     * @param outputText the neutral output text
     */
    record Produced(String outputText) implements GenerationOutcome {
      /** Compact constructor validating the text. */
      public Produced {
        Preconditions.requireNonNull(outputText, "outputText");
      }
    }

    /**
     * The provider failed with a neutral class (Doc 17 §25).
     *
     * @param failureClass the neutral failure class
     */
    record Failed(FailureClass failureClass) implements GenerationOutcome {
      /** Compact constructor validating the class. */
      public Failed {
        Preconditions.requireNonNull(failureClass, "failureClass");
      }
    }
  }
}
