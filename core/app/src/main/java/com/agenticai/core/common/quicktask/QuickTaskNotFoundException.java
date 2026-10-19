package com.agenticai.core.common.quicktask;

/**
 * No {@link QuickTaskRun} exists for this task id under the calling
 * account - either the id is wrong, the task predates this feature (run
 * before {@code QuickTaskRun} persistence existed), or it belongs to a
 * different user in this tenant (ownership-scoped lookup, see {@link
 * QuickTaskRunService#findForOwner} - deliberately returns 404 rather than
 * 403 either way, so this endpoint never confirms/denies that some OTHER
 * user's task id exists).
 */
public class QuickTaskNotFoundException extends RuntimeException {

    public QuickTaskNotFoundException(String taskId) {
        super("No task found with id \"" + taskId + "\" for this account.");
    }
}
