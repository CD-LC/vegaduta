package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.approval.ApprovalDecisionAuthorizer;
import io.vegaduta.agent.api.approval.ApprovalNotAuthorizedException;
import io.vegaduta.agent.api.approval.ApprovalRequest;

/** The requester may not decide their own request. */
public final class FourEyesAuthorizer implements ApprovalDecisionAuthorizer {

  @Override
  public void authorize(ApprovalRequest request, String decidedBy) {
    if (decidedBy.isBlank()) {
      throw new ApprovalNotAuthorizedException("A decision needs an identified approver");
    }
    if (decidedBy.equals(request.requestedBy())) {
      throw new ApprovalNotAuthorizedException(
          "Approval request "
              + request.id()
              + " was raised by '"
              + decidedBy
              + "' and may not be decided by the same person (four-eyes rule)");
    }
  }
}
