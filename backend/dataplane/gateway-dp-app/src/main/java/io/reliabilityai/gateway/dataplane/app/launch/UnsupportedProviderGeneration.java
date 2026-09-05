package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.dataplane.schemalock.api.ProviderGenerationPort;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;

/**
 * Declares structured-output generation unavailable on this node.
 *
 * <p>SchemaLock validates a structured response and, when it does not conform, asks the router to
 * generate again with the violations as feedback. That second half needs a generation driver, and
 * this repository ships the port but no adapter behind it — so a node assembled here can validate
 * structure but cannot drive a repair attempt.
 *
 * <p>Rather than leave the stage unbound, which startup validation correctly refuses, or fake a
 * success it cannot deliver, this binding answers every generation request with the non-recoverable
 * {@link FailureClass#CAPABILITY_UNSUPPORTED}. The consequence is explicit and worth stating:
 * <b>plain chat completions work fully; structured-output requests fail cleanly rather than being
 * repaired.</b> A node that needs SchemaLock in earnest must supply a real generation adapter here.
 */
final class UnsupportedProviderGeneration implements ProviderGenerationPort {

  @Override
  public GenerationOutcome generate(final GenerationCommand command) {
    return new GenerationOutcome.Failed(FailureClass.CAPABILITY_UNSUPPORTED);
  }

  @Override
  public void cancel() {
    // Nothing is ever in flight: generate() never reaches a provider.
  }
}
