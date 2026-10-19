package com.agenticai.core.agents.vision;

/**
 * The tenant's approval policy requires human approval for {@code
 * camera.capture.start} (in {@code ApprovalPolicyService}'s default-
 * sensitive set), and the requested continuous WATCH/LIVE session was
 * therefore refused - see {@link VisionSessionService#start} for why this
 * surface refuses synchronously instead of parking a pending
 * {@code ApprovalRequest}. Mapped to HTTP 403 in
 * {@code GlobalExceptionHandler}; the message tells the caller exactly which
 * policy row a tenant admin can loosen.
 */
public class VisionApprovalRequiredException extends RuntimeException {

    public VisionApprovalRequiredException(String message) {
        super(message);
    }
}
