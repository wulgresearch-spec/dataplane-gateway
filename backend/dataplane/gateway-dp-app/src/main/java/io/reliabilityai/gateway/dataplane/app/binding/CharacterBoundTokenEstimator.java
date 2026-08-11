package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.Message;
import io.reliabilityai.gateway.canonical.io.ToolDefinition;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.OptionalLong;

/**
 * Bounds prompt size by counting characters — a correct upper bound for any tokenizer, with no
 * tokenizer required.
 *
 * <p><b>Why this is sound.</b> Every subword tokenizer in use maps each token to at least one input
 * character, so a request's token count can never exceed its character count. Counting characters
 * is therefore not an estimate that might be wrong; it is a bound that is always right in the
 * direction that matters. Governance can only ever be stricter than a real tokenizer would make it,
 * never looser, which is the same asymmetry the Cost Engine's never-underestimated projection is
 * built on (Doc 22 §23).
 *
 * <p><b>Why that is still not good enough for production, stated plainly.</b> Real English text
 * runs near four characters per token, so this bound over-counts by roughly 4×. A tenant with a
 * 4,000-token context cap will be refused at about 1,000 real tokens. That is a usable safety net
 * and a poor customer experience, and the fix is to wire a real tokenizer behind {@link
 * PromptTokenEstimator}. This implementation exists so that a deployment without one enforces its
 * ceilings conservatively rather than not at all — the alternative was assuming zero tokens, which
 * silently disables every ceiling it is supposed to enforce.
 *
 * <p>A {@code perMessageOverhead} accounts for the role markers and separators a provider adds
 * around each message, which are real tokens the caller never wrote. It is operator-supplied rather
 * than guessed here, because its true value is a property of the provider's chat template.
 *
 * <p>Tool schemas are counted too: a request carrying twenty tool definitions sends those schemas
 * to the model, and a bound that ignored them would under-count exactly the requests most likely to
 * be large.
 */
public final class CharacterBoundTokenEstimator implements PromptTokenEstimator {

  private final long perMessageOverhead;

  /**
   * Creates the estimator.
   *
   * @param perMessageOverhead tokens to add per message for role markers and separators
   */
  public CharacterBoundTokenEstimator(final long perMessageOverhead) {
    this.perMessageOverhead =
        Preconditions.requireNonNegative(perMessageOverhead, "perMessageOverhead");
  }

  @Override
  public OptionalLong promptTokens(final CanonicalRequest request) {
    Preconditions.requireNonNull(request, "request");
    long bound = 0L;
    for (final Message message : request.messages()) {
      bound += message.role().length();
      bound += message.content().length();
      bound += perMessageOverhead;
    }
    for (final ToolDefinition tool : request.toolDefinitions()) {
      bound += tool.name().length();
      bound += tool.schemaJson().length();
    }
    return OptionalLong.of(bound);
  }
}
