package io.reliabilityai.gateway.dataplane.agent.internal;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.CancellationCause;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.PlanId;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunBudget;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunSecurityContext;
import io.reliabilityai.gateway.dataplane.agent.api.RunSerializationException;
import io.reliabilityai.gateway.dataplane.agent.api.RunSerializer;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import io.reliabilityai.gateway.dataplane.agent.api.RunVersion;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisionStrategy;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The durable journal format: one line per event, explicit fields, no reflection.
 *
 * <p>The grammar is {@code tag|key=value|key=value…}. Deliberately boring, and deliberately
 * hand-rolled rather than delegated to a mapping library:
 *
 * <ul>
 *   <li><b>No reflection</b> — the codebase forbids it, and a durable format is the worst place to
 *       break that rule, because it makes every field rename a silent data migration.
 *   <li><b>One line per record</b> — a torn tail after power loss is then a partial line, which the
 *       journal detects and truncates. A multi-line format would leave a half-record that parses.
 *   <li><b>Explicit tags</b> — {@code run.created}, not a class name, so refactoring a type is not
 *       a schema change.
 *   <li><b>Field order fixed by the writer</b> — two nodes encoding the same event produce
 *       byte-identical lines, which is what lets a digest over the journal mean anything.
 * </ul>
 *
 * <p>Round-tripping is a correctness requirement, not a nicety: replay folds these events, so a
 * lost or reordered field makes a recovered run behave differently from the run that crashed.
 */
public final class CanonicalRunSerializer implements RunSerializer {

  private static final char FIELD = '|';
  private static final char ASSIGN = '=';
  private static final char ESCAPE = '\\';

  @Override
  public int formatVersion() {
    return RunVersion.CURRENT_JOURNAL_FORMAT;
  }

  @Override
  public String serialize(final RunEvent event) {
    Preconditions.requireNonNull(event, "event");
    final Writer out = new Writer(event.tag());
    switch (event) {
      case RunEvent.RunCreated created -> {
        out.put("plan", created.version().planId().value());
        out.putLong("planV", created.version().planVersion());
        out.putLong("fmt", created.version().journalFormat());
        out.putLong("maxSteps", created.bounds().maxSteps());
        out.putLong("maxDepth", created.bounds().maxDepth());
        out.putLong("maxFanout", created.bounds().maxFanout());
        out.put("wallClock", created.bounds().wallClock().toString());
        out.putLong("spendBound", created.bounds().spendMicros());
        out.putLong("grant", created.budget().grantMicros());
        out.putLong("consumed", created.budget().consumedMicros());
        out.putLong("reserve", created.budget().reserveMicros());
        out.put("principal", created.security().principal().value());
        out.put("org", created.security().tenant().org());
        out.put("tenant", created.security().tenant().tenant());
        out.put("workspace", nullToEmpty(created.security().tenant().workspace()));
        out.put("project", nullToEmpty(created.security().tenant().project()));
        out.put("correlation", created.security().correlationId().value());
        out.put("caps", String.join(",", created.security().capabilities()));
        out.put("parent", created.parent().map(RunId::value).orElse(""));
        out.putLong("depth", created.depth());
      }
      case RunEvent.RunQueued ignored -> {
        // No fields beyond the timestamp every record carries.
      }
      case RunEvent.StepScheduled scheduled -> {
        out.putLong("step", scheduled.stepId().index());
        out.putLong("planIdx", scheduled.planStepIndex());
        out.put("name", scheduled.stepName());
        out.put("kind", scheduled.kind().name());
        out.putLong("attempt", scheduled.attempt());
        out.put("ref", scheduled.sessionRef());
      }
      case RunEvent.StepCompleted completed -> {
        out.putLong("step", completed.stepId().index());
        out.put("name", completed.stepName());
        out.put("value", completed.value());
        out.put("digest", completed.valueDigest());
        out.putLong("cost", completed.costMicros());
        out.putBool("tainted", completed.tainted());
      }
      case RunEvent.StepFailed failed -> {
        out.putLong("step", failed.stepId().index());
        out.put("name", failed.stepName());
        out.put("failure", failed.failure().name());
        out.put("reason", failed.reason());
        out.putLong("cost", failed.costMicros());
      }
      case RunEvent.StepSkipped skipped -> {
        out.putLong("step", skipped.stepId().index());
        out.put("name", skipped.stepName());
        out.put("failure", skipped.failure().name());
      }
      case RunEvent.StepCancelled cancelled -> {
        out.putLong("step", cancelled.stepId().index());
        out.put("name", cancelled.stepName());
        out.put("cause", cancelled.cause().name());
        out.putLong("cost", cancelled.costMicros());
      }
      case RunEvent.ToolArtifactProduced produced -> {
        out.putLong("step", produced.stepId().index());
        out.put("artifact", produced.artifactId());
        out.put("capability", produced.capability());
        out.put("digest", produced.contentDigest());
        out.putBool("tainted", produced.tainted());
      }
      case RunEvent.SupervisionApplied supervision -> {
        out.putLong("step", supervision.stepId().index());
        out.put("strategy", supervision.strategy().name());
        out.put("decision", supervision.decision());
        out.putLong("restarts", supervision.restartCount());
      }
      case RunEvent.BranchTaken branch -> {
        out.putLong("step", branch.stepId().index());
        out.put("name", branch.stepName());
        out.putBool("outcome", branch.outcome());
        out.put("target", branch.targetStep());
      }
      case RunEvent.RunParked parked -> out.put("wakeAt", parked.wakeAt().toString());
      case RunEvent.RunResumed resumed -> out.putLong("parkedNanos", resumed.parkedNanos());
      case RunEvent.RunCheckpointed checkpoint -> out.putLong("offset", checkpoint.historyOffset());
      case RunEvent.RunCancelled cancelled -> {
        out.put("cause", cancelled.cause().name());
        out.put("reason", cancelled.reason());
      }
      case RunEvent.RunTerminated terminated -> {
        out.put("reason", terminated.reason().name());
        out.put("state", terminated.state().name());
        out.putLong("steps", terminated.stepsExecuted());
        out.putLong("cost", terminated.totalCostMicros());
      }
    }
    out.put("at", event.at().toString());
    return out.finish();
  }

  @Override
  public RunEvent deserialize(final String encoded, final RunId runId) {
    Preconditions.requireNonNull(encoded, "encoded");
    Preconditions.requireNonNull(runId, "runId");

    final int split = encoded.indexOf(FIELD);
    final String tag = split < 0 ? encoded : encoded.substring(0, split);
    final Map<String, String> fields = split < 0 ? Map.of() : parse(encoded.substring(split + 1));
    final Instant at = instant(fields, "at");

    return switch (tag) {
      case "run.created" -> readCreated(fields, at);
      case "run.queued" -> new RunEvent.RunQueued(at);
      case "step.scheduled" ->
          new RunEvent.StepScheduled(
              stepId(runId, fields),
              (int) longValue(fields, "planIdx"),
              text(fields, "name"),
              StepKind.valueOf(text(fields, "kind")),
              (int) longValue(fields, "attempt"),
              text(fields, "ref"),
              at);
      case "step.completed" ->
          new RunEvent.StepCompleted(
              stepId(runId, fields),
              text(fields, "name"),
              text(fields, "value"),
              text(fields, "digest"),
              longValue(fields, "cost"),
              boolValue(fields, "tainted"),
              at);
      case "step.failed" ->
          new RunEvent.StepFailed(
              stepId(runId, fields),
              text(fields, "name"),
              FailureClass.valueOf(text(fields, "failure")),
              text(fields, "reason"),
              longValue(fields, "cost"),
              at);
      case "step.skipped" ->
          new RunEvent.StepSkipped(
              stepId(runId, fields),
              text(fields, "name"),
              FailureClass.valueOf(text(fields, "failure")),
              at);
      case "step.cancelled" ->
          new RunEvent.StepCancelled(
              stepId(runId, fields),
              text(fields, "name"),
              CancellationCause.valueOf(text(fields, "cause")),
              longValue(fields, "cost"),
              at);
      case "tool.artifact" ->
          new RunEvent.ToolArtifactProduced(
              stepId(runId, fields),
              text(fields, "artifact"),
              text(fields, "capability"),
              text(fields, "digest"),
              boolValue(fields, "tainted"),
              at);
      case "supervision.applied" ->
          new RunEvent.SupervisionApplied(
              stepId(runId, fields),
              SupervisionStrategy.valueOf(text(fields, "strategy")),
              text(fields, "decision"),
              (int) longValue(fields, "restarts"),
              at);
      case "branch.taken" ->
          new RunEvent.BranchTaken(
              stepId(runId, fields),
              text(fields, "name"),
              boolValue(fields, "outcome"),
              text(fields, "target"),
              at);
      case "run.parked" -> new RunEvent.RunParked(instant(fields, "wakeAt"), at);
      case "run.resumed" -> new RunEvent.RunResumed(longValue(fields, "parkedNanos"), at);
      case "run.checkpointed" -> new RunEvent.RunCheckpointed(longValue(fields, "offset"), at);
      case "run.cancelled" ->
          new RunEvent.RunCancelled(
              CancellationCause.valueOf(text(fields, "cause")), text(fields, "reason"), at);
      case "run.terminated" ->
          new RunEvent.RunTerminated(
              TerminalReason.valueOf(text(fields, "reason")),
              RunState.valueOf(text(fields, "state")),
              (int) longValue(fields, "steps"),
              longValue(fields, "cost"),
              at);
      // An unknown tag is refused rather than skipped. Skipping would silently drop a fact from a
      // history that replay then folds into the wrong answer, and the run would diverge for a
      // reason
      // nobody could trace back to a forward-compatibility shortcut taken here.
      default -> throw new RunSerializationException("unknown event tag: " + tag);
    };
  }

  private static RunEvent readCreated(final Map<String, String> fields, final Instant at) {
    final TenantScope tenant =
        new TenantScope(
            text(fields, "org"),
            text(fields, "tenant"),
            emptyToNull(text(fields, "workspace")),
            emptyToNull(text(fields, "project")));

    final String caps = text(fields, "caps");
    final Set<String> capabilities = new TreeSet<>();
    if (!caps.isEmpty()) {
      for (final String capability : caps.split(",", -1)) {
        if (!capability.isEmpty()) {
          capabilities.add(capability);
        }
      }
    }

    final String parent = text(fields, "parent");
    return new RunEvent.RunCreated(
        new RunVersion(
            PlanId.of(text(fields, "plan")),
            (int) longValue(fields, "planV"),
            (int) longValue(fields, "fmt")),
        new RunBounds(
            (int) longValue(fields, "maxSteps"),
            (int) longValue(fields, "maxDepth"),
            (int) longValue(fields, "maxFanout"),
            Duration.parse(text(fields, "wallClock")),
            longValue(fields, "spendBound")),
        new RunBudget(
            longValue(fields, "grant"),
            longValue(fields, "consumed"),
            longValue(fields, "reserve")),
        new RunSecurityContext(
            new PrincipalId(text(fields, "principal")),
            tenant,
            new CorrelationId(text(fields, "correlation")),
            capabilities),
        parent.isEmpty() ? Optional.empty() : Optional.of(RunId.of(parent)),
        (int) longValue(fields, "depth"),
        at);
  }

  private static StepId stepId(final RunId runId, final Map<String, String> fields) {
    return StepId.of(runId, (int) longValue(fields, "step"));
  }

  private static String text(final Map<String, String> fields, final String key) {
    final String value = fields.get(key);
    if (value == null) {
      throw new RunSerializationException("missing field '" + key + "'");
    }
    return value;
  }

  private static long longValue(final Map<String, String> fields, final String key) {
    try {
      return Long.parseLong(text(fields, key));
    } catch (final NumberFormatException malformed) {
      throw new RunSerializationException("field '" + key + "' is not a number", malformed);
    }
  }

  private static boolean boolValue(final Map<String, String> fields, final String key) {
    return "1".equals(text(fields, key));
  }

  private static Instant instant(final Map<String, String> fields, final String key) {
    try {
      return Instant.parse(text(fields, key));
    } catch (final java.time.format.DateTimeParseException malformed) {
      throw new RunSerializationException("field '" + key + "' is not an instant", malformed);
    }
  }

  private static String nullToEmpty(final String value) {
    return value == null ? "" : value;
  }

  private static String emptyToNull(final String value) {
    return value.isEmpty() ? null : value;
  }

  /** Accumulates fields in writer order, so the same event always encodes to the same bytes. */
  private static final class Writer {
    private final StringBuilder out = new StringBuilder(128);

    Writer(final String tag) {
      out.append(tag);
    }

    void put(final String key, final String value) {
      out.append(FIELD).append(key).append(ASSIGN).append(escape(value));
    }

    void putLong(final String key, final long value) {
      put(key, Long.toString(value));
    }

    void putBool(final String key, final boolean value) {
      put(key, value ? "1" : "0");
    }

    String finish() {
      return out.toString();
    }
  }

  /**
   * Escapes the three structural characters and the newline.
   *
   * <p>The newline matters most: the journal is line-delimited, so an unescaped newline inside a
   * model's output would split one record into two and corrupt every subsequent offset.
   */
  private static String escape(final String value) {
    final StringBuilder escaped = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case ESCAPE -> escaped.append("\\\\");
        case FIELD -> escaped.append("\\p");
        case ASSIGN -> escaped.append("\\e");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private static Map<String, String> parse(final String body) {
    final Map<String, String> fields = new LinkedHashMap<>();
    final StringBuilder token = new StringBuilder();
    String key = null;
    boolean escaped = false;

    for (int i = 0; i < body.length(); i++) {
      final char c = body.charAt(i);
      if (escaped) {
        token.append(unescape(c));
        escaped = false;
        continue;
      }
      switch (c) {
        case ESCAPE -> escaped = true;
        case ASSIGN -> {
          if (key == null) {
            key = token.toString();
            token.setLength(0);
          } else {
            // A bare '=' inside a value means the writer failed to escape it. Appending it would
            // silently produce a wrong value; refusing says which record is bad.
            throw new RunSerializationException("unescaped '=' in value for key '" + key + "'");
          }
        }
        case FIELD -> {
          if (key == null) {
            throw new RunSerializationException("field separator before any key");
          }
          fields.put(key, token.toString());
          token.setLength(0);
          key = null;
        }
        default -> token.append(c);
      }
    }
    if (escaped) {
      throw new RunSerializationException("record ends with a dangling escape");
    }
    if (key != null) {
      fields.put(key, token.toString());
    }
    return fields;
  }

  private static char unescape(final char c) {
    return switch (c) {
      case '\\' -> ESCAPE;
      case 'p' -> FIELD;
      case 'e' -> ASSIGN;
      case 'n' -> '\n';
      case 'r' -> '\r';
      default -> throw new RunSerializationException("unknown escape: \\" + c);
    };
  }
}
