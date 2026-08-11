package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.ClockPort;

/**
 * The neutral collaborators the gateway hands a provider module when it builds its adapter.
 *
 * <p>Deliberately small, and deliberately containing no transport. The gateway does not hand a
 * provider an HTTP client, because the moment it does, the gateway owns an opinion about how
 * providers talk to the world — protocol version, connection pooling, redirect policy — and the
 * first provider that speaks gRPC, or a local socket, or nothing at all, has to be special-cased in
 * the composition root. A provider builds and closes its own transport and reports it through
 * {@link ProviderInstance#close}.
 *
 * <p>What is here is what only the gateway can supply: credentials it materialises per request, the
 * capability snapshot the operator published, telemetry, and the single injected clock. Everything
 * else is the provider's own business.
 *
 * @param credentials the per-request credential seam
 * @param capabilities the read-only capability snapshot the adapter consumes
 * @param telemetry the adapter telemetry sink
 * @param clock the injected clock — the node's only source of time
 */
public record ProviderRuntimeContext(
    CredentialPort credentials,
    CapabilitySnapshotPort capabilities,
    AdapterTelemetryPort telemetry,
    ClockPort clock) {

  /** Validates the context. */
  public ProviderRuntimeContext {
    Preconditions.requireNonNull(credentials, "credentials");
    Preconditions.requireNonNull(capabilities, "capabilities");
    Preconditions.requireNonNull(telemetry, "telemetry");
    Preconditions.requireNonNull(clock, "clock");
  }
}
