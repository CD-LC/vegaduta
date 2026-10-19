package io.vegaduta.agent.core.classification;

import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.risk.ToolClassificationDecision;
import io.vegaduta.agent.api.risk.ToolClassificationSource;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One place that answers "what is this tool?" for the gate and the pipeline. Applies run-time
 * overrides (for example the lethal-trifecta rule forcing approval on a sink) over the configured
 * sources, and the policy default in {@link GovernanceMode#WARN}.
 */
public final class ClassificationResolver {

  private final ToolClassificationSource source;
  private final GovernancePolicy policy;
  private final Map<String, ToolClassification> overrides;

  public ClassificationResolver(ToolClassificationSource source, GovernancePolicy policy) {
    this(source, policy, new ConcurrentHashMap<>());
  }

  private ClassificationResolver(
      ToolClassificationSource source,
      GovernancePolicy policy,
      Map<String, ToolClassification> overrides) {
    this.source = source;
    this.policy = policy;
    this.overrides = overrides;
  }

  /**
   * A resolver for another policy that shares this resolver's sources and run-time overrides, so a
   * candidate policy evaluated in shadow mode sees the same classifications (including a
   * lethal-trifecta override) as the live one. Overrides applied on either are visible to both.
   */
  public ClassificationResolver sharing(GovernancePolicy otherPolicy) {
    return new ClassificationResolver(source, otherPolicy, overrides);
  }

  public ToolClassificationDecision resolve(String toolName) {
    ToolClassification override = overrides.get(toolName);
    if (override != null) {
      return new ToolClassificationDecision.Classified(override);
    }
    Optional<ToolClassification> found = source.classify(toolName);
    if (found.isPresent()) {
      return new ToolClassificationDecision.Classified(found.get());
    }
    return ToolClassificationDecision.Blocked.unclassified(toolName);
  }

  /**
   * The classification the pipeline should act on. For an unclassified tool this is the policy
   * default (medium risk, side-effecting, not replay-safe) so that warn mode still errs on the safe
   * side.
   */
  public ToolClassification effective(String toolName) {
    ToolClassificationDecision decision = resolve(toolName);
    if (decision instanceof ToolClassificationDecision.Classified classified) {
      return classified.classification();
    }
    return defaultFor(toolName);
  }

  public ToolClassification defaultFor(String toolName) {
    return ToolClassification.builder(toolName)
        .risk(policy.defaultRisk())
        .sideEffecting(true)
        .idempotent(false)
        .build();
  }

  public boolean isClassified(String toolName) {
    return resolve(toolName) instanceof ToolClassificationDecision.Classified;
  }

  /** Replaces the classification of one tool until {@link #clearOverride(String)}. */
  public void override(ToolClassification classification) {
    overrides.put(classification.toolName(), classification);
  }

  public void clearOverride(String toolName) {
    overrides.remove(toolName);
  }

  public GovernancePolicy policy() {
    return policy;
  }
}
