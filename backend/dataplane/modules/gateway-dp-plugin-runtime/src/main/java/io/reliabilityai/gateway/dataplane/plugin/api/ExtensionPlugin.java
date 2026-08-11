package io.reliabilityai.gateway.dataplane.plugin.api;

import java.util.Map;

/**
 * A plugin that contributes an advisory signal at one of the frozen extension points (Doc 28 §EPC).
 *
 * <p>The return type is the whole story: a bounded map of content-free strings. A plugin cannot
 * return a decision, a route, a verdict, a response or a mutation, because there is no type here
 * that can express one. Doc 28 EPC-5 says the owning stage decides — this signature is how that is
 * enforced rather than merely documented.
 */
public interface ExtensionPlugin extends Plugin {

  /**
   * Contributes an advisory signal at the given frozen extension point.
   *
   * <p>Runs under an enforced deadline and quota. Throwing, overrunning or breaching a quota
   * isolates the invocation and discards the contribution; the owning stage proceeds exactly as if
   * this plugin did not exist (Doc 28 §EPFC).
   *
   * @param request the advisory input, identical for every plugin at this point
   * @return the content-free advisory contribution
   * @throws Exception if the plugin fails; the contribution is discarded
   */
  Map<String, String> contributeAt(ExtensionRequest request) throws Exception;
}
