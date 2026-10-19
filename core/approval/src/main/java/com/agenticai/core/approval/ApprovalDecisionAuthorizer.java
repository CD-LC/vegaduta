package com.agenticai.core.approval;

/**
 * An extra check, contributed by the module that raised a kind of approval, on who may decide it.
 * {@link ApprovalController} runs every authorizer that {@link #supports} a request, for callers
 * who are not approval admins, after its own admin-only and self-approval checks. It lets a module
 * that knows the resource behind an approval (a workflow run, say) apply that resource's access
 * rules without this module depending on it.
 */
public interface ApprovalDecisionAuthorizer {

    boolean supports(ApprovalRequest request);

    /** Throws {@link ApprovalNotAuthorizedException} when the current caller may not decide it. */
    void authorize(ApprovalRequest request);
}
