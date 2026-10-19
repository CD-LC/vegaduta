package io.vegaduta.agent.api.approval;

/**
 * Decides who may approve or reject a request. The default implementation applies the four-eyes
 * rule: the requester may not decide their own request. Hosts replace it to check roles or group
 * membership.
 */
@FunctionalInterface
public interface ApprovalDecisionAuthorizer {

  /**
   * @throws ApprovalNotAuthorizedException when {@code decidedBy} may not decide {@code request}
   */
  void authorize(ApprovalRequest request, String decidedBy);

  /** Anyone may decide anything. For tests. */
  static ApprovalDecisionAuthorizer permitAll() {
    return (request, decidedBy) -> {};
  }
}
