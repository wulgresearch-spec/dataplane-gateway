package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * A plugin that executes tools (Doc 28 §15).
 *
 * <p>This is the seam the future Agent Runtime drives. It is deliberately <b>not</b> bound to any
 * request-pipeline stage: Doc 28 EPC-3 forbids the post-invoke and response-transform points that
 * model-driven tool calling would require, so tool execution is offered as a runtime capability and
 * left for an ADR-gated caller rather than wired into the hot path where it would be a bypass.
 */
public interface ToolPlugin extends Plugin {

  /**
   * Executes one tool call and returns its response.
   *
   * <p>Runs under an enforced deadline and quota inside the plugin's sandbox. The implementation
   * should honour {@link Thread#interrupt()} — that is how cancellation reaches an in-process
   * plugin, and a plugin that swallows interrupts will simply be waited out and then abandoned.
   *
   * @param invocation the unit of work
   * @return the tool response
   * @throws Exception if the tool fails; the runtime classifies and isolates it
   */
  ToolResponse invoke(ToolInvocation invocation) throws Exception;
}
