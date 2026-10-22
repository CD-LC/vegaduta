package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import io.vegaduta.agent.api.GovernanceException;
import java.util.Objects;
import java.util.UUID;

/**
 * Thrown by {@link DefaultApprovalGate#request} when a fail-closed hook at {@code
 * ON_APPROVAL_REQUESTED} (for example an {@link ApprovalNotifier} built with {@code
 * propagateFailures(true)} and registered {@code FailMode.CLOSED}) blocked the request: the
 * approvers could not be reached, so the request was marked {@code FAILED} instead of waiting
 * unseen. {@code GovernedTool} turns it into a denied result.
 */
@Experimental
public final class ApprovalNotificationBlockedException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  private final UUID approvalId;
  private final String reason;

  public ApprovalNotificationBlockedException(UUID approvalId, String reason) {
    super("approval " + approvalId + " could not be notified: " + reason);
    this.approvalId = Objects.requireNonNull(approvalId, "approvalId");
    this.reason = Objects.requireNonNull(reason, "reason");
  }

  public UUID approvalId() {
    return approvalId;
  }

  public String reason() {
    return reason;
  }
}
