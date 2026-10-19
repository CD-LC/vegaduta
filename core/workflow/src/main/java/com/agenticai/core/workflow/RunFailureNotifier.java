package com.agenticai.core.workflow;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.NotificationCategory;
import com.agenticai.core.audit.NotificationService;
import com.agenticai.core.audit.NotificationSeverity;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Records a failed workflow run where people will see it: a {@link PlatformIssue} on the admin Issues portal and a
 * bell notification for the tenant's admins. Both are best effort - a failure here is logged, never rethrown.
 */
final class RunFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(RunFailureNotifier.class);

    private final PlatformIssueService issueService;
    // ObjectProvider: the notification bell (Tier-3) is a best-effort side
    // signal layered onto the PlatformIssue record, never a reason a run
    // that already failed should throw again.
    private final ObjectProvider<NotificationService> notificationService;

    RunFailureNotifier(PlatformIssueService issueService, ObjectProvider<NotificationService> notificationService) {
        this.issueService = issueService;
        this.notificationService = notificationService;
    }

    // Admin Issues portal (#425/#426) - recorded synchronously, right where the
    // run's own status is set (same "sync where needed" reasoning as
    // PlatformIssueService's own javadoc: a run that just failed shouldn't
    // depend on Kafka/the outbox being healthy to be visible for triage).
    // Best-effort: a bug in issue-recording must never turn a completed
    // WorkflowRun save into a failed request.
    void record(WorkflowRun run, String message) {
        PlatformIssue recorded = null;
        try {
            // sourceId + title are deliberately keyed on the WORKFLOW (definition), not this one run -
            // see PlatformIssueService#fingerprint's javadoc: repeated failures of the same workflow
            // should accumulate onto one issue row, not mint a new row per run id. The specific failing
            // run id rides in the message instead, so triage can still jump straight to it.
            recorded = issueService.record(IssueSource.WORKFLOW_RUN, run.getWorkflowId().toString(), IssueSeverity.ERROR,
                "Workflow run failed", "Run " + run.getId() + ": " + message);
        } catch (RuntimeException recordingFailure) {
            log.warn("Failed to record platform issue for failed workflow run {}", run.getId(), recordingFailure);
        }
        // Notifications (Tier-3): the issue record is the admin's triage
        // queue; this is the tenant's bell. "Failed runs require polling
        // the console" was the audit gap - broadcast to tenant-admins
        // (recipientUserId null, the approver/admin pool) with the issue
        // id wired as relatedIssueId so the bell can deep-link triage.
        try {
            NotificationService service = notificationService.getIfAvailable();
            if (service != null) {
                service.create(null, NotificationCategory.WORKFLOW, "Workflow run failed",
                    "Run " + run.getId() + ": " + message,
                    NotificationSeverity.ERROR, null, recorded == null ? null : recorded.getId());
            }
        } catch (RuntimeException notifyFailure) {
            log.warn("Failed to create notification for failed workflow run {}", run.getId(), notifyFailure);
        }
    }
}
