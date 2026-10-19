package io.vegaduta.agent.api.autonomy;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.risk.RiskLabel;
import io.vegaduta.agent.api.risk.ToolClassification;
import java.util.Objects;

/**
 * The least autonomy an agent needs before it may run a tool, derived from the tool's
 * classification by a pure function so the mapping is testable and reportable.
 *
 * <p>The mapping:
 *
 * <ul>
 *   <li>not side-effecting: {@link AutonomyLevel#L1_OBSERVE}
 *   <li>side-effecting, {@link RiskLabel#LOW} or {@link RiskLabel#MEDIUM}: {@link
 *       AutonomyLevel#L3_ACT_BOUNDED}
 *   <li>side-effecting, {@link RiskLabel#HIGH} or {@link RiskLabel#CRITICAL}: {@link
 *       AutonomyLevel#L4_ACT_AUTONOMOUS}, unless the classification says {@code requiresApproval},
 *       which keeps it at {@link AutonomyLevel#L3_ACT_BOUNDED} with a mandatory approval
 *   <li>{@link RiskLabel#CRITICAL} always carries {@code approvalRequired}, matching the label's
 *       own contract, so no autonomy level runs a CRITICAL effect without a human
 * </ul>
 *
 * <p>{@code approvalRequired} mirrors the classification's own {@code requiresApproval} flag: a
 * human must approve each call even when the agent's level satisfies the minimum.
 *
 * @param minimumLevel the lowest level that may run the tool at all
 * @param approvalRequired whether a human must approve each call regardless of level
 */
@Experimental
public record AutonomyRequirement(AutonomyLevel minimumLevel, boolean approvalRequired) {

  public AutonomyRequirement {
    Objects.requireNonNull(minimumLevel, "minimumLevel");
  }

  /** The minimum level a tool with this classification needs. Pure; never throws. */
  public static AutonomyRequirement forClassification(ToolClassification classification) {
    Objects.requireNonNull(classification, "classification");
    boolean approval = classification.requiresApproval();
    if (!classification.sideEffecting()) {
      return new AutonomyRequirement(AutonomyLevel.L1_OBSERVE, approval);
    }
    if (classification.risk().atLeast(RiskLabel.HIGH)) {
      // CRITICAL is "always approval-gated" (RiskLabel contract): even a fully autonomous agent
      // needs a human for each call, whatever the requiresApproval flag says.
      return approval
          ? new AutonomyRequirement(AutonomyLevel.L3_ACT_BOUNDED, true)
          : new AutonomyRequirement(
              AutonomyLevel.L4_ACT_AUTONOMOUS, classification.risk() == RiskLabel.CRITICAL);
    }
    return new AutonomyRequirement(AutonomyLevel.L3_ACT_BOUNDED, approval);
  }

  /** True when an agent at {@code level} meets the minimum. Approval is a separate question. */
  public boolean satisfiedBy(AutonomyLevel level) {
    return level.atLeast(minimumLevel);
  }
}
