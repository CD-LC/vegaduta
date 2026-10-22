package io.vegaduta.agent.api.approval;

import io.vegaduta.agent.api.GovernanceException;

/**
 * Thrown when {@link ApprovalGate#decide} is called for a request that already has a terminal
 * status ({@code APPROVED}, {@code REJECTED}, {@code EXPIRED} or {@code FAILED}): decisions are
 * made once, so callers do not accidentally overwrite an earlier verdict.
 */
public class ApprovalAlreadyDecidedException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  public ApprovalAlreadyDecidedException(ApprovalRequest request) {
    super("Approval request " + request.id() + " is already " + request.status());
  }
}
