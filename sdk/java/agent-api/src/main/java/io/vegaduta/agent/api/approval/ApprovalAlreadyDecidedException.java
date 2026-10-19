package io.vegaduta.agent.api.approval;

import io.vegaduta.agent.api.GovernanceException;

public class ApprovalAlreadyDecidedException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  public ApprovalAlreadyDecidedException(ApprovalRequest request) {
    super("Approval request " + request.id() + " is already " + request.status());
  }
}
