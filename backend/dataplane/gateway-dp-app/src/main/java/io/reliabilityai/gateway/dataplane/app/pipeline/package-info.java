/**
 * The co-located data-plane pipeline assembly (Doc 06 §8, §9.1 — Platform-owned; AD-020).
 *
 * <p>This package contains the single straight-line chain that runs one request through the frozen
 * mandatory stages in {@link io.reliabilityai.gateway.dataplane.app.MandatoryStage} order. It is
 * <b>not</b> an orchestrator: there is no dynamic dispatch, no stage registry, no runtime graph, no
 * workflow engine and no plugin execution. The order is fixed at compile time by the sequence of
 * statements in {@link io.reliabilityai.gateway.dataplane.app.pipeline.RequestPipeline}, so it can
 * be read and reviewed as ordinary code.
 *
 * <p><b>Doc 32 §SAQ-8 constraint.</b> Doc 32 is a description of the frozen order and explicitly
 * forbids any component treating it as an orchestration authority. This assembly therefore derives
 * its ordering from the frozen topology (Doc 06 §8) and the non-bypass invariant (AD-018) as
 * encoded in {@code MandatoryStage}; it cites Doc 32 for nothing and claims authority over nothing.
 * Every decision remains its frozen owner's — the chain only sequences calls and carries results
 * between them.
 *
 * <p><b>Fail-closed.</b> The first stage that refuses ends the request. No downstream stage runs,
 * nothing is metered, nothing is published, and the caller receives a canonical refusal naming the
 * stage.
 */
package io.reliabilityai.gateway.dataplane.app.pipeline;
