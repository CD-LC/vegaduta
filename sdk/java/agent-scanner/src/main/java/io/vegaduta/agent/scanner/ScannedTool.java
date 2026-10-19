package io.vegaduta.agent.scanner;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.risk.ToolClassification;
import java.util.Objects;

/**
 * One row of the risk map: a found tool and its effective classification.
 *
 * @param tool where the tool was found
 * @param classification the declared classification, or the policy default when {@code classified}
 *     is false (what {@code WARN} mode would assume at run time)
 * @param classified the tool carries {@code @Governed}
 * @param approvalGated the gate would ask a human: {@code requiresApproval}, or side-effecting with
 *     risk at or above the policy threshold
 */
@Experimental
public record ScannedTool(
    FoundTool tool, ToolClassification classification, boolean classified, boolean approvalGated) {

  public ScannedTool {
    Objects.requireNonNull(tool, "tool");
    Objects.requireNonNull(classification, "classification");
  }

  public String toolName() {
    return tool.toolName();
  }
}
