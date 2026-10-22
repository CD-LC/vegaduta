package io.vegaduta.agent.api.approval;

import io.vegaduta.agent.api.GovernanceException;

/**
 * Thrown when a principal tries to decide a request it is not allowed to decide - not a named
 * approver, not in the required role, or not a different-enough principal for a four-eyes
 * rule.
 */
public class ApprovalNotAuthorizedException extends GovernanceException {

  private static final long serialVersionUID = 1L;

  public ApprovalNotAuthorizedException(String message) {
    super(message);
  }
}
