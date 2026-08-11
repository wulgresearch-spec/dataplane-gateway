package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.io.ToolDefinition;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.app.pipeline.Operation;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistryPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns an authenticated inbound request into the question governance can actually answer.
 *
 * <p>Every field the Governance Engine evaluates originates here, and every one of them comes from
 * a named upstream — the authenticated identity, the caller's declared request, the capability
 * registry, the Cost Engine, the token bound. Nothing is defaulted into existence. Where an
 * upstream cannot answer, the corresponding field is left <em>unknown</em> rather than filled with
 * a placeholder, and the engine refuses any mandatory policy that depended on it.
 *
 * <p>That last point is the reason this class exists as a separate object rather than as a block of
 * code inside the pipeline. Assembling the governance question involves reading three subsystems,
 * any of which can be unavailable, and the handling of "unavailable" has to be uniform and
 * testable. Inlined into the pipeline it would be six lines of null-checks nobody could test in
 * isolation, and the first time one of them defaulted to zero, a spend ceiling would quietly stop
 * firing.
 *
 * <p><b>It never throws.</b> A capability registry that fails, a cost engine that fails, a token
 * estimator that fails — each degrades one field to unknown and the request is still assembled. An
 * assembler that threw would put an exception on the path between authentication and admission, and
 * whoever caught it would have to decide whether to admit, which is the one decision that must not
 * happen outside the engine.
 *
 * <p><b>What is deliberately not carried:</b> the messages, the tool schemas, the raw parameter
 * map. Governance decides from the request's shape, never its content, which is what keeps every
 * decision, violation and audit record content-free by construction rather than by redaction (Doc
 * 14 §7.1). The one place content is touched is the token bound, which reduces it to a single
 * integer.
 */
public final class GovernanceAdmissionAssembler {

  /** Capability names the caller may assert that map onto a governed capability flag. */
  private static final String VISION_CAPABILITY = "vision";

  private static final String REASONING_CAPABILITY = "reasoning";

  private final CapabilityRegistryPort capabilities;
  private final AdmissionCostProjector costs;
  private final GovernanceScopeResolver scopes;

  /**
   * Creates the assembler.
   *
   * @param capabilities the route registry supplying the candidate provider set
   * @param costs the Cost Engine seam supplying the prompt bound and projected spend
   * @param scopes the resolver supplying the request's hierarchy address
   */
  public GovernanceAdmissionAssembler(
      final CapabilityRegistryPort capabilities,
      final AdmissionCostProjector costs,
      final GovernanceScopeResolver scopes) {
    this.capabilities = Preconditions.requireNonNull(capabilities, "capabilities");
    this.costs = Preconditions.requireNonNull(costs, "costs");
    this.scopes = Preconditions.requireNonNull(scopes, "scopes");
  }

  /**
   * Assembles the admission question.
   *
   * @param inbound what the caller supplied
   * @param principal the authenticated principal
   * @param tenant the resolved tenant scope
   * @return the governance question, with unresolvable facts left unknown
   */
  public PolicyRequest assemble(
      final RequestExecution.Inbound inbound,
      final PrincipalContext principal,
      final TenantContext tenant) {
    Preconditions.requireNonNull(inbound, "inbound");
    Preconditions.requireNonNull(principal, "principal");
    Preconditions.requireNonNull(tenant, "tenant");

    final CanonicalModelId model = inbound.request().canonicalModelId();
    final OptionalLong promptTokens = costs.promptTokens(inbound.request());
    final OptionalLong projectedCost =
        costs.project(
            inbound.requestContext(),
            tenant,
            model,
            promptTokens,
            inbound.declaredMaxOutputTokens());
    final Operation operation = inbound.operation();

    final PolicyRequest.Builder builder =
        PolicyRequest.builder()
            .requestContext(inbound.requestContext())
            .principal(principal)
            .tenant(tenant)
            .scopeChain(scopes.resolve(principal, tenant))
            .model(model)
            .region(inbound.requestContext().region())
            .tools(toolNames(inbound))
            .requiredComplianceRegimes(inbound.requiredCompliance())
            .streaming(inbound.mode() == Mode.STREAM)
            .jsonMode(inbound.outputSchema().isPresent())
            .containsPii(inbound.piiDeclared())
            .reasoning(inbound.requiredCapabilities().contains(REASONING_CAPABILITY))
            .vision(inbound.requiredCapabilities().contains(VISION_CAPABILITY))
            .imageGeneration(operation == Operation.IMAGE_GENERATION)
            .audio(operation == Operation.AUDIO)
            .embedding(operation == Operation.EMBEDDING)
            .fineTuning(operation == Operation.FINE_TUNING)
            .batch(operation == Operation.BATCH)
            .quantity(PolicyRequest.Quantity.CONTEXT_TOKENS, promptTokens)
            .quantity(PolicyRequest.Quantity.OUTPUT_TOKENS, inbound.declaredMaxOutputTokens())
            .quantity(PolicyRequest.Quantity.PROJECTED_COST_MICROS, projectedCost);

    return candidateRoutes(model)
        .map(builder::candidateProviders)
        .orElseGet(builder::unknownCandidateProviders)
        .build();
  }

  /**
   * The opaque route ids that could serve this model, from the capability registry.
   *
   * <p>Candidates rather than a selection, because governance runs before the Router and the
   * selection does not exist yet. Governance narrows this set; the Router then picks within what
   * survives, which is exactly the narrow-only handoff the resolved context specifies (Doc 21 §36.1
   * RGC-5).
   *
   * <p>An unpublished registry yields empty — meaning <em>unknown</em>, not <em>none</em>. Provider
   * policy then refuses rather than passing on an empty candidate set it never actually saw.
   */
  private Optional<Set<String>> candidateRoutes(final CanonicalModelId model) {
    try {
      return capabilities
          .currentSnapshot()
          .map(
              snapshot -> {
                final Set<String> ids = new LinkedHashSet<>();
                for (final CapabilityDescriptor descriptor : snapshot.descriptors()) {
                  if (descriptor.canonicalModelId().equals(model)) {
                    ids.add(descriptor.candidateId());
                  }
                }
                return ids;
              });
    } catch (final RuntimeException unavailable) {
      return Optional.empty();
    }
  }

  /**
   * The names of the tools the request may invoke.
   *
   * <p>Names only. The argument schemas travel with the request to the provider and are
   * SchemaLock's concern; carrying them into governance would put caller-authored JSON into the
   * audit trail for no decision that reads it.
   */
  private static Set<String> toolNames(final RequestExecution.Inbound inbound) {
    final Set<String> names = new TreeSet<>();
    for (final ToolDefinition tool : inbound.request().toolDefinitions()) {
      names.add(tool.name());
    }
    return names;
  }
}
