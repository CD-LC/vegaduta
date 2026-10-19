package com.agenticai.core.approval;

import java.util.UUID;

/**
 * Thrown by {@code ApprovalController} when a caller may not be the one who
 * decides a particular request. Distinct from every other approval exception
 * in this package (not-found, already-decided) - this one is specifically
 * about "the request is real and still pending, but you're not allowed to be
 * the one who decides it." Mapped to HTTP 403 by {@code
 * GlobalExceptionHandler}.
 *
 * <p>Originally raised for one reason only ({@code "adminOnly": true} in a
 * workflow Approval node's {@code payloadJson}). The approval-gate hardening
 * pass added three more, all in
 * {@code ApprovalController#enforceApproverAuthorization}: a gated agent
 * tool call ({@code mcp_tool.*}), an unparseable payload (default-deny), and
 * self-approval by the same subject that requested the action. The
 * {@code (UUID, String)} constructor exists so the 403 body says which of
 * those it was, instead of a single misleading "requires a tenant-admin"
 * message for every case.
 */
public class ApprovalNotAuthorizedException extends RuntimeException {

    public ApprovalNotAuthorizedException(UUID id) {
        super("Approval request " + id + " requires a tenant-admin to decide");
    }

    public ApprovalNotAuthorizedException(UUID id, String reason) {
        super("Approval request " + id + " may not be decided by you: " + reason);
    }
}
