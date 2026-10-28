package com.agenticai.core.approval;

import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds approved actions a dead process left half-done. {@link HumanApprovalGateService} commits the decision and only
 * then runs the action, so a crash in between used to leave a request that looked finished and never ran. Two cases,
 * told apart by the markers on the request (migration V674):
 *
 * <ul>
 *   <li><b>never started</b> - safe to run, once, if the decision is recent ({@code max-recovery-age}); an older one is
 *       closed as failed instead, because it was approved against a situation that may have changed;
 *   <li><b>started, end never recorded</b> - what it did is unknown, so it is <i>not</i> run again (a second send or
 *       charge is worse than a reported gap): closed as dead-lettered and the conversation is told.
 * </ul>
 */
@Component
public class ApprovalRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRecoveryJob.class);

    private static final String JOB_LOCK_NAME = "approval-recovery";

    static final String STALE_REASON =
        "This approved action was not run: the platform stopped after the decision and by the time it came back the "
            + "approval was too old to act on safely. Nothing was done. If the action is still needed, ask for it again.";
    static final String INTERRUPTED_REASON =
        "This approved action started but the platform stopped before it reported how it ended, so it is not known "
            + "whether it happened. It was NOT run again, to avoid doing it twice. Check the result before asking again.";

    private final ApprovalRecoveryProperties properties;
    private final TenantSchemaIterator schemaIterator;
    private final ApprovalRequestRepository requestRepository;
    private final HumanApprovalGateService approvalGateService;
    private final PgAdvisoryJobLock jobLock;

    public ApprovalRecoveryJob(
            ApprovalRecoveryProperties properties,
            TenantSchemaIterator schemaIterator,
            ApprovalRequestRepository requestRepository,
            HumanApprovalGateService approvalGateService,
            PgAdvisoryJobLock jobLock) {
        this.properties = properties;
        this.schemaIterator = schemaIterator;
        this.requestRepository = requestRepository;
        this.approvalGateService = approvalGateService;
        this.jobLock = jobLock;
    }

    @Scheduled(
            fixedDelayString = "${app.approvals.recovery.sweep-interval:PT1M}",
            initialDelayString = "${app.approvals.recovery.initial-delay:PT2M}")
    public void recoverInterruptedApprovals() {
        if (!properties.isEnabled()) {
            return;
        }
        jobLock.tryRunExclusive(JOB_LOCK_NAME, () -> sweep(Instant.now()));
    }

    /** One pass over every active tenant; returns how many requests it acted on. */
    int sweep(Instant now) {
        AtomicInteger handled = new AtomicInteger();
        schemaIterator.forEachActiveTenantSchema(schema -> {
            Instant recentDecisions = now.minus(properties.getMaxRecoveryAge());
            List<ApprovalRequest> unstarted = requestRepository.findApprovedNeverStarted(
                    now.minus(properties.getUnstartedGrace()), PageRequest.of(0, properties.getBatchSize()));
            for (ApprovalRequest request : unstarted) {
                try {
                    if (request.getDecidedAt() != null && request.getDecidedAt().isBefore(recentDecisions)) {
                        handled.addAndGet(approvalGateService.abandonStaleApproval(request, STALE_REASON) ? 1 : 0);
                    } else {
                        log.warn("Approved request {} (action type '{}') was never started - running it once (schema '{}')",
                                request.getId(), request.getActionType(), schema);
                        approvalGateService.runUnstartedApproval(request);
                        handled.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    log.error("Could not recover approval request {} in schema '{}'", request.getId(), schema, e);
                }
            }
            List<ApprovalRequest> interrupted = requestRepository.findApprovedStartedNeverFinished(
                    now.minus(properties.getStartedStaleAfter()), PageRequest.of(0, properties.getBatchSize()));
            for (ApprovalRequest request : interrupted) {
                try {
                    if (approvalGateService.abandonInterruptedApproval(request, INTERRUPTED_REASON)) {
                        log.warn("Approved request {} (action type '{}') started and never reported its end - closed as "
                                + "interrupted, not re-run (schema '{}')", request.getId(), request.getActionType(), schema);
                        handled.incrementAndGet();
                    }
                } catch (RuntimeException e) {
                    log.error("Could not close interrupted approval request {} in schema '{}'",
                            request.getId(), schema, e);
                }
            }
        });
        return handled.get();
    }
}
