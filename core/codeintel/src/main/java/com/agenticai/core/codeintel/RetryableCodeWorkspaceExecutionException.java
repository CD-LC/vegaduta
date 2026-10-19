package com.agenticai.core.codeintel;

import com.agenticai.core.approval.RetryableActionFailure;

/**
 * The code-intel service itself was unreachable, or returned a 5xx, while
 * an already-approved workspace run/apply was being carried out - a
 * transport-level failure, not a policy decision or a bad payload. This is
 * plausibly transient (the sidecar restarting, a network blip), so unlike
 * {@link CodeWorkspaceExecutionException}'s other throw sites this
 * implements {@link RetryableActionFailure}, opting into
 * {@code HumanApprovalGateService}'s existing exponential-backoff retry
 * (Autonomy/Guardrails Strategy Stage C) instead of failing permanently on
 * the first attempt - same reasoning {@code RetryableChannelDeliveryException}
 * already applies to a Twilio/Meta API call failing.
 */
public class RetryableCodeWorkspaceExecutionException extends CodeWorkspaceExecutionException implements RetryableActionFailure {

    public RetryableCodeWorkspaceExecutionException(String message) {
        super(message);
    }
}
