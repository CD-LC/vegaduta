package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.RunContext;
import io.vegaduta.agent.api.approval.ApprovalRequest;
import io.vegaduta.agent.api.hook.HookEvent;
import io.vegaduta.agent.api.hook.HookPoint;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link MessageFormat} renders: the approval hook event flattened, enriched with the
 * request's summary when the notifier can read it back from the approval store.
 *
 * @param point {@link HookPoint#ON_APPROVAL_REQUESTED} or {@link HookPoint#ON_APPROVAL_DECIDED}
 * @param approvalId the approval request id
 * @param actionType the request's action type, for example {@code "tool:refund_order"}
 * @param toolName the tool when the action is a tool call, or {@code null}
 * @param summary the one line written for the approver, or {@code null} when unknown
 * @param payload the request's payload JSON
 * @param decision {@code "approved"} or {@code "rejected"} for decided events, else {@code null}
 * @param decidedBy who decided, or {@code null}
 * @param reason the approver's reason, or {@code null}
 * @param context the run that asked
 */
@Experimental
public record ApprovalNotification(
    HookPoint point,
    String approvalId,
    String actionType,
    @Nullable String toolName,
    @Nullable String summary,
    String payload,
    @Nullable String decision,
    @Nullable String decidedBy,
    @Nullable String reason,
    RunContext context) {

  public ApprovalNotification {
    Objects.requireNonNull(point, "point");
    Objects.requireNonNull(approvalId, "approvalId");
    Objects.requireNonNull(actionType, "actionType");
    Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(context, "context");
  }

  /**
   * Flattens a hook event as {@link DefaultApprovalGate} emits it.
   *
   * @param request the stored request, used for the summary, action type and reason; or {@code
   *     null} when the notifier has no store
   */
  public static ApprovalNotification from(HookEvent event, @Nullable ApprovalRequest request) {
    String approvalId = event.attributes().getOrDefault("approvalId", "");
    String actionType = event.attributes().get("actionType");
    if (actionType == null) {
      actionType =
          request != null
              ? request.actionType()
              : event.toolName() != null
                  ? DefaultApprovalGate.toolActionType(event.toolName())
                  : "unknown";
    }
    return new ApprovalNotification(
        event.point(),
        approvalId,
        actionType,
        event.toolName(),
        request != null ? request.summary() : null,
        event.payload(),
        event.attributes().get("decision"),
        event.attributes().get("decidedBy"),
        request != null ? request.decisionReason() : null,
        event.context());
  }

  public boolean isDecision() {
    return point == HookPoint.ON_APPROVAL_DECIDED;
  }

  /** The tool name, or the action type when the action is not a tool call. */
  public String what() {
    return toolName != null ? toolName : actionType;
  }
}
