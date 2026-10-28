package io.vegaduta.agent.api.approval;

/**
 * Decides who may approve or reject a request. The default (what {@code Governance} installs
 * unless told otherwise) applies the four-eyes rule: the requester may not decide their own
 * request. Hosts replace it to check roles or group membership, or opt out explicitly with {@link
 * #allowAny()}.
 */
@FunctionalInterface
public interface ApprovalDecisionAuthorizer {

  /**
   * @throws ApprovalNotAuthorizedException when {@code decidedBy} may not decide {@code request}
   */
  void authorize(ApprovalRequest request, String decidedBy);

  /**
   * The explicit opt-out of the two-person rule: anyone, including the requester, may decide
   * anything. For single-operator tools and tests; the Python SDK spells it {@code allow_any}.
   */
  static ApprovalDecisionAuthorizer allowAny() {
    return (request, decidedBy) -> {};
  }

  /** Same as {@link #allowAny()}; kept for existing callers. */
  static ApprovalDecisionAuthorizer permitAll() {
    return allowAny();
  }
}
