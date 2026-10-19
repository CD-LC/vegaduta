package io.vegaduta.agent.api.tool;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of a governed tool call.
 *
 * @param status what happened
 * @param output what the model receives; for non-{@link ToolStatus#OK} statuses this is a
 *     human-readable explanation the model can act on
 * @param reason machine-readable reason for a refusal, or {@code null}
 * @param approvalId the approval request id when {@code status} is {@link
 *     ToolStatus#PENDING_APPROVAL}, else {@code null}
 */
public record ToolResult(
    ToolStatus status, String output, @Nullable String reason, @Nullable String approvalId) {

  public ToolResult {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(output, "output");
  }

  public static ToolResult ok(String output) {
    return new ToolResult(ToolStatus.OK, output, null, null);
  }

  public static ToolResult replayed(String output) {
    return new ToolResult(ToolStatus.REPLAYED, output, null, null);
  }

  public static ToolResult denied(String reason, String explanation) {
    return new ToolResult(ToolStatus.DENIED, explanation, reason, null);
  }

  public static ToolResult pendingApproval(String approvalId, String explanation) {
    return new ToolResult(
        ToolStatus.PENDING_APPROVAL, explanation, "approval_required", approvalId);
  }

  public static ToolResult budgetExceeded(String explanation) {
    return new ToolResult(ToolStatus.BUDGET_EXCEEDED, explanation, "budget_exceeded", null);
  }

  public static ToolResult error(String reason, String explanation) {
    return new ToolResult(ToolStatus.ERROR, explanation, reason, null);
  }

  public boolean succeeded() {
    return status == ToolStatus.OK || status == ToolStatus.REPLAYED;
  }
}
