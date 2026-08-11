package io.reliabilityai.gateway.dataplane.memory.api;

/**
 * The seven kinds of memory (AD-026 §4).
 *
 * <p>Each constant differs from the others in <em>lifetime</em>, <em>default scope</em>, <em>write
 * pattern</em> and <em>default retrieval mode</em>. A kind that differed in none of those would be
 * a metadata tag wearing a type's clothes, and modelling it as a type would misdescribe the domain.
 *
 * <p><b>A type is not an authorization boundary</b> (AD-026 §4.2). Two records of different types
 * in the same scope are equally reachable to a caller authorized for that scope. Treating a type as
 * a permission would create a second, weaker access-control model beside the governance engine's.
 */
public enum MemoryType {

  /**
   * The scratchpad for one active exchange. High churn, frequently overwritten.
   *
   * <p>The only type an adapter may hold in a volatile tier, because losing it costs a retry rather
   * than a fact.
   */
  WORKING(false, true, RetrievalMode.SCOPE),

  /**
   * The record of one bounded interaction. Retrieved by recency, because a session is sequential.
   */
  SESSION(true, false, RetrievalMode.SCOPE),

  /** Durable facts about a user or workspace that outlive any session. */
  LONG_TERM(true, false, RetrievalMode.HYBRID),

  /**
   * Meaning-addressed knowledge.
   *
   * <p>The only type for which an embedding is mandatory rather than optional: a semantic memory
   * with no vector is a keyword memory wearing a costume.
   */
  SEMANTIC(true, false, RetrievalMode.SEMANTIC),

  /**
   * What happened, and when. Time-anchored rather than session-anchored, so an episode may span
   * sessions.
   */
  EPISODIC(true, false, RetrievalMode.TIME),

  /**
   * What a capability returned, keyed by capability and arguments.
   *
   * <p><b>Always tainted</b> (AD-026 §10.4). Tool output is untrusted content, and tool output that
   * has been made durable is untrusted content that will be retrieved later by a caller who has
   * forgotten where it came from.
   */
  TOOL(true, false, RetrievalMode.METADATA),

  /** The state of a unit of work, against a caller-supplied opaque task key. */
  TASK(true, false, RetrievalMode.METADATA);

  private final boolean durable;
  private final boolean overwritable;
  private final RetrievalMode defaultMode;

  MemoryType(final boolean durable, final boolean overwritable, final RetrievalMode defaultMode) {
    this.durable = durable;
    this.overwritable = overwritable;
    this.defaultMode = defaultMode;
  }

  /**
   * Reports whether records of this type must survive a restart.
   *
   * @return false only for {@link #WORKING}, which an adapter may keep in a volatile tier
   */
  public boolean durable() {
    return durable;
  }

  /**
   * Reports whether a write may overwrite an existing record in place rather than versioning it.
   *
   * @return true only for {@link #WORKING}
   */
  public boolean overwritable() {
    return overwritable;
  }

  /**
   * Returns the retrieval mode used when a query does not name one.
   *
   * @return the default mode for this type
   */
  public RetrievalMode defaultMode() {
    return defaultMode;
  }

  /**
   * Reports whether this type requires an embedding at write time.
   *
   * @return true only for {@link #SEMANTIC}
   */
  public boolean requiresEmbedding() {
    return this == SEMANTIC;
  }

  /**
   * Reports whether records of this type are untrusted content by construction.
   *
   * <p>Unconditional for {@link #TOOL}. A caller cannot declare a tool memory trusted, because the
   * caller is not the party that produced the content.
   *
   * @return true only for {@link #TOOL}
   */
  public boolean alwaysTainted() {
    return this == TOOL;
  }
}
