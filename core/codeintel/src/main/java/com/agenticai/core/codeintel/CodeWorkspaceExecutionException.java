package com.agenticai.core.codeintel;

/**
 * A code-intel workspace run/apply, already approved (by a human via
 * core/approval, or by an explicit per-tenant policy override), could not
 * actually be carried out - a malformed payload, code-intel's own
 * independent guardrail policy denying it (403 {@code PolicyDeniedError}),
 * or code-intel's own guardrail additionally asking for its OWN approval
 * (428 {@code ApprovalRequiredError}, a separate, disconnected approval
 * system - see {@link RunCodeWorkspaceApprovalExecutor}'s javadoc). None of
 * these are transient - retrying without a human resolving the underlying
 * cause would just fail the same way again, so this stays plain (not
 * {@link com.agenticai.core.approval.RetryableActionFailure}); see {@link
 * RetryableCodeWorkspaceExecutionException} for the one failure mode here
 * that IS worth retrying.
 */
public class CodeWorkspaceExecutionException extends RuntimeException {

    public CodeWorkspaceExecutionException(String message) {
        super(message);
    }

    public CodeWorkspaceExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
