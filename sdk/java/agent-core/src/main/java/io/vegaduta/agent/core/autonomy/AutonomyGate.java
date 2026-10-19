package io.vegaduta.agent.core.autonomy;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.autonomy.AutonomyLevel;
import io.vegaduta.agent.api.autonomy.AutonomyRequirement;
import io.vegaduta.agent.api.gate.ToolDispatchGate;
import io.vegaduta.agent.api.gate.Verdict;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import io.vegaduta.agent.api.tool.ToolCall;
import io.vegaduta.agent.core.classification.ClassificationResolver;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A {@link ToolDispatchGate} that enforces the agent's {@link AutonomyLevel} and then defers to an
 * optional delegate (normally the policy gate). The autonomy rules, for the classification the
 * {@link ClassificationResolver} reports for the tool:
 *
 * <ol>
 *   <li>{@link AutonomyLevel#L2_RECOMMEND} and the tool is side-effecting: {@code REQUIRE_APPROVAL}
 *       ({@value #REASON_RECOMMEND}); recommending means proposing the effect for a human to
 *       execute
 *   <li>{@link AutonomyLevel#L3_ACT_BOUNDED} and the tool is side-effecting at {@link
 *       RiskLabel#HIGH}: {@code REQUIRE_APPROVAL} ({@value #REASON_BOUNDED_HIGH_RISK})
 *   <li>the level is below the {@link AutonomyRequirement} minimum: {@code DENY} with reason {@code
 *       "autonomy:<level><<minimum>"}, e.g. {@code "autonomy:L1_OBSERVE<L3_ACT_BOUNDED"}
 *   <li>the requirement says a human must approve each call: {@code REQUIRE_APPROVAL} ({@value
 *       #REASON_REQUIRES_APPROVAL})
 *   <li>otherwise the autonomy rules allow the call
 * </ol>
 *
 * <p>The delegate then runs. A {@code DENY} from either side wins, then any {@code
 * REQUIRE_APPROVAL}, else {@code ALLOW}. The L3 tool allow-list is the delegate's job (see {@code
 * PolicyToolDispatchGate#allow}).
 */
@Experimental
public final class AutonomyGate implements ToolDispatchGate {

  public static final String REASON_PREFIX = "autonomy:";
  public static final String REASON_RECOMMEND = REASON_PREFIX + "recommend_only";
  public static final String REASON_BOUNDED_HIGH_RISK = REASON_PREFIX + "bounded_high_risk";
  public static final String REASON_REQUIRES_APPROVAL = REASON_PREFIX + "requires_approval";

  private final AutonomyResolver autonomy;
  private final ClassificationResolver classifications;
  private final @Nullable ToolDispatchGate delegate;

  /** A gate that enforces autonomy only. */
  public AutonomyGate(AutonomyResolver autonomy, ClassificationResolver classifications) {
    this(autonomy, classifications, null);
  }

  /** A gate that enforces autonomy and then consults {@code delegate}. */
  public AutonomyGate(
      AutonomyResolver autonomy,
      ClassificationResolver classifications,
      @Nullable ToolDispatchGate delegate) {
    this.autonomy = Objects.requireNonNull(autonomy, "autonomy");
    this.classifications = Objects.requireNonNull(classifications, "classifications");
    this.delegate = delegate;
  }

  @Override
  public Verdict check(ToolCall call, RunContext context) {
    AutonomyLevel level = autonomy.resolve(context);
    ToolClassification classification = classifications.effective(call.toolName());
    Verdict own = evaluate(level, classification);
    if (own.outcome() == Verdict.Outcome.DENY || delegate == null) {
      return own;
    }
    Verdict other = delegate.check(call, context);
    if (other.outcome() == Verdict.Outcome.DENY) {
      return other;
    }
    if (own.outcome() == Verdict.Outcome.REQUIRE_APPROVAL) {
      return own;
    }
    return other;
  }

  /**
   * The autonomy verdict for a level and a classification, without the delegate. Pure; the report
   * uses it to show what each level can reach.
   */
  public static Verdict evaluate(AutonomyLevel level, ToolClassification classification) {
    AutonomyRequirement requirement = AutonomyRequirement.forClassification(classification);
    if (classification.sideEffecting()) {
      if (level == AutonomyLevel.L2_RECOMMEND) {
        return Verdict.requireApproval(REASON_RECOMMEND);
      }
      if (level == AutonomyLevel.L3_ACT_BOUNDED && classification.risk() == RiskLabel.HIGH) {
        return Verdict.requireApproval(REASON_BOUNDED_HIGH_RISK);
      }
    }
    if (!requirement.satisfiedBy(level)) {
      return Verdict.deny(REASON_PREFIX + level.name() + "<" + requirement.minimumLevel().name());
    }
    if (requirement.approvalRequired()) {
      return Verdict.requireApproval(REASON_REQUIRES_APPROVAL);
    }
    return Verdict.allow();
  }
}
