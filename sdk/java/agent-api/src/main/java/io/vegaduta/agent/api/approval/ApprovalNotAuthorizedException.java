package io.vegaduta.agent.api.approval;

import io.vegaduta.agent.api.GovernanceException;

public class ApprovalNotAuthorizedException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  public ApprovalNotAuthorizedException(String message) {
    super(message);
  }
}
