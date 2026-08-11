package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * A tool plugin that can emit events as it works (Doc 28 §30).
 *
 * <p>Extends {@link ToolPlugin} rather than replacing it, so every streaming plugin also has a
 * unary path. A caller that does not want a stream is never forced to consume one, and the runtime
 * never has to synthesize a unary response by draining a stream it did not need.
 */
public interface StreamingToolPlugin extends ToolPlugin {

  /**
   * Executes one tool call, emitting events as it goes.
   *
   * <p>The implementation must emit exactly one terminal event ({@link PluginEvent.Completed} or
   * {@link PluginEvent.Failed}) and must stop as soon as {@link PluginEventSink#emit} returns false
   * — that is the consumer telling it nobody is reading any more.
   *
   * <p>If the method returns without a terminal event, the runtime supplies a {@link
   * PluginEvent.Failed}. It never invents a {@code Completed}: claiming success for a plugin that
   * did not claim it is exactly the fabrication Doc 28 PRT-INV forbids.
   *
   * @param invocation the unit of work
   * @param sink where events are written
   * @throws Exception if the tool fails; the runtime classifies and isolates it
   */
  void invokeStreaming(ToolInvocation invocation, PluginEventSink sink) throws Exception;
}
