package io.reliabilityai.gateway.dataplane.memory.pii;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.PiiClassifierPort;

/**
 * Adapts the detection engine to {@code PiiClassifierPort} (AD-029 §3).
 *
 * <p>Two things happen here, and both are lossy in ways worth stating.
 *
 * <p><b>The rich result is flattened.</b> The engine produces a severity in five levels, a set of
 * categories, and a span list. {@code DataClassification} has six values and no category dimension,
 * so the mapping in {@link #classificationOf} throws most of that away at the boundary. The full
 * result remains available through {@link #detect} for anything that can use it; C17 cannot,
 * because its port cannot express it. Recorded as B46.
 *
 * <p><b>The tenant is unknown.</b> {@code classify(String)} receives a body and nothing else, so it
 * runs the built-in and organization rules only. <b>Tenant rules are unreachable through this
 * method</b>, and therefore unreachable from C17 as currently wired. {@link #detect(TenantScope,
 * String)} is the tenant-aware entry point and is available to any caller that knows who it is
 * acting for. Recorded as B45, and it is the largest gap in this milestone.
 */
public final class RuleBasedPiiClassifier implements PiiClassifierPort {

  /** What a detected span is replaced with. Fixed-width, so the mask does not leak the length. */
  private static final String MASK = "[redacted]";

  private final PiiRuleRegistry registry;

  /**
   * Creates the classifier over a rule registry.
   *
   * @param registry the compiled rules, per tenant
   */
  public RuleBasedPiiClassifier(final PiiRuleRegistry registry) {
    this.registry = Preconditions.requireNonNull(registry, "registry");
  }

  @Override
  public Classification classify(final String body) {
    return classify(null, body);
  }

  /**
   * Classifies a body using a tenant's rules.
   *
   * <p>Not on the port, because the port has no parameter for it. This is the method a composition
   * root should call once the port can carry a tenant.
   *
   * @param tenant whose rules to apply, or null for the organization default
   * @param body the plaintext to classify
   * @return what was found, flattened to what the port can express
   */
  public Classification classify(final TenantScope tenant, final String body) {
    if (body == null) {
      return new Classification(DataClassification.UNCLASSIFIED, "", false);
    }
    final PiiDetection detection = detect(tenant, body);
    if (!detection.complete()) {
      // A truncated or abandoned scan says nothing about what it did not read. Reporting the part
      // it
      // did read as the answer would be the classic fail-open: a body engineered to be long enough,
      // or to trip a pathological rule, would classify as clean. UNCLASSIFIED refuses the write
      // (MEM-21) instead.
      return new Classification(DataClassification.UNCLASSIFIED, body, false);
    }
    final DataClassification classification = classificationOf(detection);
    if (!detection.any()) {
      return Classification.clean(classification, body);
    }
    return new Classification(classification, mask(body, detection), true);
  }

  /**
   * Scans a body with a tenant's rules and returns the full result.
   *
   * @param tenant whose rules to apply, or null for the organization default
   * @param body the plaintext to scan
   * @return every span, the severity, and the categories
   */
  public PiiDetection detect(final TenantScope tenant, final String body) {
    return registry.engineFor(tenant).detect(body);
  }

  /**
   * Flattens a detection to the six values the port can carry.
   *
   * <p>The ordering matters. Secrets are checked first because {@code SECRET} is the one value C17
   * treats as unstorable outright, and a body containing both an API key and an email address must
   * be refused rather than merely sealed.
   *
   * <p>A body with nothing found maps to {@code INTERNAL}, not {@code PUBLIC}. "No personal data
   * was detected" is not "safe to disclose" — a strategy document contains neither, and the
   * detector finding nothing is also what a detector does when it is simply blind to the content in
   * front of it. {@code PUBLIC} is a claim this engine is not in a position to make.
   *
   * @param detection the engine's result
   * @return the flattened classification
   */
  static DataClassification classificationOf(final PiiDetection detection) {
    if (detection.categories().contains(PiiCategory.SECRET)
        || detection.categories().contains(PiiCategory.AUTHENTICATION)) {
      return DataClassification.SECRET;
    }
    return switch (detection.severity()) {
      case CRITICAL, HIGH -> DataClassification.SENSITIVE_PII;
      case MEDIUM, LOW -> DataClassification.PII;
      case NONE -> DataClassification.INTERNAL;
    };
  }

  /**
   * Replaces every detected span with a fixed mask.
   *
   * <p>Masking is a mechanical consequence of knowing the spans, not a policy decision — whether
   * the masked body is ever used stays with {@code PiiAction} in C17. It is produced here because
   * the port requires a redacted body and {@code MemoryWritePipeline} uses it when policy says
   * {@code REDACT}; returning the body unchanged would silently stop redaction from happening at
   * all.
   *
   * @param body the original text
   * @param detection the spans to mask
   * @return the masked text
   */
  private static String mask(final String body, final PiiDetection detection) {
    final StringBuilder masked = new StringBuilder(body.length());
    int at = 0;
    for (final PiiSpan span : detection.spans()) {
      if (span.start() < at) {
        continue;
      }
      masked.append(body, at, span.start()).append(MASK);
      at = span.end();
    }
    masked.append(body, at, body.length());
    return masked.toString();
  }
}
