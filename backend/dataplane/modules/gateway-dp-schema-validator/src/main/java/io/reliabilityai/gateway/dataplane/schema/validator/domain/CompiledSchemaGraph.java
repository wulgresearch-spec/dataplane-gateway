package io.reliabilityai.gateway.dataplane.schema.validator.domain;

import java.util.Map;

/**
 * A fully compiled schema: the root node, the {@code $defs} table its references resolve against,
 * and the node count used as the complexity signal.
 *
 * <p>Immutable and thread-safe, so one compiled graph serves every concurrent validation of that
 * schema without copying or locking.
 *
 * @param root the root schema node
 * @param definitions the {@code $defs} entries, keyed by definition name
 * @param complexity the total compiled node count
 */
public record CompiledSchemaGraph(
    SchemaNode root, Map<String, SchemaNode> definitions, int complexity) {

  /** Defensively copies the definitions table. */
  public CompiledSchemaGraph {
    definitions = Map.copyOf(definitions);
  }

  /**
   * Resolves a node, following a {@code $ref} indirection when present.
   *
   * @param node the node to resolve
   * @return the referenced node, or the node itself when it is not a reference
   */
  public SchemaNode resolve(final SchemaNode node) {
    if (node == null || node.ref() == null) {
      return node;
    }
    final SchemaNode target = definitions.get(node.ref());
    // Compilation proved every reference resolves, so a miss here would be a compiler bug, not
    // input.
    return target == null ? node : target;
  }
}
