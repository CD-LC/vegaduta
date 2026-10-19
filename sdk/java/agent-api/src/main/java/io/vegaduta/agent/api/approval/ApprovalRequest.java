package io.vegaduta.agent.api.approval;

import io.vegaduta.agent.api.RunContext;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A side-effecting action waiting for, or decided by, a human.
 *
 * @param id unique id
 * @param actionType what kind of action, for example {@code "tool:refund_order"}; used to find the
 *     {@link ApprovalActionExecutor} that runs it after approval
 * @param summary one line for the approver
 * @param payloadJson everything needed to execute the action later (tool name and arguments)
 * @param context the run that asked
 * @param requestedBy the principal that asked
 * @param status lifecycle state
 * @param createdAt when it was recorded
 * @param expiresAt after this the request can no longer be approved
 * @param decidedAt when a human decided, or {@code null}
 * @param decidedBy who decided, or {@code null}
 * @param decisionReason free text from the approver, or {@code null}
 * @param resultJson the action's output after execution, or {@code null}
 */
public record ApprovalRequest(
    UUID id,
    String actionType,
    String summary,
    String payloadJson,
    RunContext context,
    String requestedBy,
    ApprovalStatus status,
    Instant createdAt,
    Instant expiresAt,
    @Nullable Instant decidedAt,
    @Nullable String decidedBy,
    @Nullable String decisionReason,
    @Nullable String resultJson) {

  public ApprovalRequest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(actionType, "actionType");
    Objects.requireNonNull(summary, "summary");
    Objects.requireNonNull(payloadJson, "payloadJson");
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(requestedBy, "requestedBy");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  public static ApprovalRequest pending(
      String actionType,
      String summary,
      String payloadJson,
      RunContext context,
      Instant now,
      Instant expiresAt) {
    return new ApprovalRequest(
        UUID.randomUUID(),
        actionType,
        summary,
        payloadJson,
        context,
        context.principalOrAnonymous(),
        ApprovalStatus.PENDING,
        now,
        expiresAt,
        null,
        null,
        null,
        null);
  }

  public boolean isExpired(Instant now) {
    return status == ApprovalStatus.PENDING && !now.isBefore(expiresAt);
  }

  public ApprovalRequest withDecision(
      ApprovalStatus newStatus, Instant at, String by, @Nullable String reason) {
    return new ApprovalRequest(
        id,
        actionType,
        summary,
        payloadJson,
        context,
        requestedBy,
        newStatus,
        createdAt,
        expiresAt,
        at,
        by,
        reason,
        resultJson);
  }

  public ApprovalRequest withStatus(ApprovalStatus newStatus) {
    return new ApprovalRequest(
        id,
        actionType,
        summary,
        payloadJson,
        context,
        requestedBy,
        newStatus,
        createdAt,
        expiresAt,
        decidedAt,
        decidedBy,
        decisionReason,
        resultJson);
  }

  public ApprovalRequest withResult(ApprovalStatus newStatus, @Nullable String result) {
    return new ApprovalRequest(
        id,
        actionType,
        summary,
        payloadJson,
        context,
        requestedBy,
        newStatus,
        createdAt,
        expiresAt,
        decidedAt,
        decidedBy,
        decisionReason,
        result);
  }
}
