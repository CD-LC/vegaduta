package com.agenticai.core.workflow;

/**
 * A workflow-approval decision could not be turned into a run resume (or a
 * rejection could not be applied) - thrown by {@link
 * WorkflowApprovalActionExecutor} and recorded by {@code
 * HumanApprovalGateService} as the request's execution failure, exactly like
 * any other executor's failure.
 *
 * <p>Deliberately NOT {@code RetryableActionFailure}: every condition that
 * raises this (payload predates resume support, run/definition row missing,
 * graph unparseable) is stable - re-running it on {@code
 * RetryFailedActionsJob}'s timer would fail identically forever. A human
 * reading the recorded message is the fix path.
 */
public class WorkflowApprovalResumeException extends RuntimeException {

    public WorkflowApprovalResumeException(String message) {
        super(message);
    }

    public WorkflowApprovalResumeException(String message, Throwable cause) {
        super(message, cause);
    }
}
