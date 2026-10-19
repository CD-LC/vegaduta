package io.vegaduta.agent.core.gate;

import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.risk.ToolClassificationDecision;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.GovernanceMode;
import io.vegaduta.agent.core.GovernancePolicy;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The reference gate. Order of evaluation:
 *
 * <ol>
 *   <li>deny list: {@code DENY}
 *   <li>allow list (when non-empty, anything not on it): {@code DENY}
 *   <li>unclassified in {@link GovernanceMode#STRICT}: {@code DENY}
 *   <li>classification says {@code requiresApproval}: {@code REQUIRE_APPROVAL}
 *   <li>risk at or above the policy threshold: {@code REQUIRE_APPROVAL}
 *   <li>otherwise {@code ALLOW}
 * </ol>
 */
public final class PolicyToolDispatchGate implements ToolDispatchGate {

  public static final String REASON_DENY_LIST = "deny_list";
  public static final String REASON_NOT_ALLOW_LISTED = "not_allow_listed";
  public static final String REASON_UNCLASSIFIED = "unclassified";
  public static final String REASON_QUARANTINED = "quarantined";
  public static final String REASON_REQUIRES_APPROVAL = "requires_approval";
  public static final String REASON_RISK_THRESHOLD = "risk_threshold";

  private final ClassificationResolver resolver;
  private final GovernancePolicy policy;
  private final Set<String> allowList = new CopyOnWriteArraySet<>();
  private final Set<String> denyList = new CopyOnWriteArraySet<>();

  public PolicyToolDispatchGate(ClassificationResolver resolver) {
    this.resolver = resolver;
    this.policy = resolver.policy();
  }

  public PolicyToolDispatchGate allow(String... toolNames) {
    allowList.addAll(Set.of(toolNames));
    return this;
  }

  public PolicyToolDispatchGate deny(String... toolNames) {
    denyList.addAll(Set.of(toolNames));
    return this;
  }

  /** The allow list, read-only. */
  public Set<String> allowList() {
    return java.util.Collections.unmodifiableSet(allowList);
  }

  /** The deny list, read-only. */
  public Set<String> denyList() {
    return java.util.Collections.unmodifiableSet(denyList);
  }

  @Override
  public Verdict check(ToolCall call, RunContext context) {
    String name = call.toolName();
    if (denyList.contains(name)) {
      return Verdict.deny(REASON_DENY_LIST);
    }
    if (!allowList.isEmpty() && !allowList.contains(name)) {
      return Verdict.deny(REASON_NOT_ALLOW_LISTED);
    }
    ToolClassificationDecision decision = resolver.resolve(name);
    ToolClassification classification;
    if (decision instanceof ToolClassificationDecision.Blocked blocked) {
      if (blocked.reason() == ToolClassificationDecision.Reason.QUARANTINED) {
        return Verdict.deny(REASON_QUARANTINED);
      }
      if (policy.mode() == GovernanceMode.STRICT) {
        return Verdict.deny(REASON_UNCLASSIFIED);
      }
      classification = resolver.defaultFor(name);
    } else {
      classification = ((ToolClassificationDecision.Classified) decision).classification();
    }
    if (classification.requiresApproval()) {
      return Verdict.requireApproval(REASON_REQUIRES_APPROVAL);
    }
    if (classification.sideEffecting()
        && classification.risk().atLeast(policy.approvalRequiredFrom())) {
      return Verdict.requireApproval(REASON_RISK_THRESHOLD);
    }
    return Verdict.allow();
  }
}
