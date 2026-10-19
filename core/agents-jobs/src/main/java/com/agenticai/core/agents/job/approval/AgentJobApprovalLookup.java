package com.agenticai.core.agents.job.approval;

import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * A SECOND Spring Data repository over the existing {@link ApprovalRequest}
 * entity, declared here rather than as three new methods on {@code
 * ApprovalRequestRepository}.
 *
 * <p><b>Why a second interface instead of editing the first one.</b> Two
 * repository interfaces on one entity is ordinary Spring Data - each is its own
 * proxy over the same {@code EntityManager} - and it is what keeps approval
 * parking from reaching into {@code core/approval}, a module owned by nobody in
 * this workstream and shared by ten channel adapters, the workflow engine and
 * the code-workspace executors. The alternative (adding
 * {@code findByRequestedByAndStatus...} to {@code ApprovalRequestRepository})
 * would put an agent-jobs concern in a file every one of those callers compiles
 * against, for no behavioural gain.
 *
 * <p><b>{@code Repository} and not {@code JpaRepository}, deliberately.</b> This
 * is a READ-ONLY view: approval parking never creates, decides or deletes an
 * approval request - {@code HumanApprovalGateService} owns every write to that
 * table, and it must stay the only writer or the audit trail stops being the
 * record of what happened. Extending the marker interface makes that structural
 * instead of a convention someone has to remember.
 *
 * <p>The lookup key is {@code requested_by}, which for a gated agent tool call
 * is the label {@code GuardedToolCallback} writes verbatim:
 * {@code "agent:<agentId>:session:job:<jobId>"} - see {@code
 * AgentJobSession#requestedByLabel}, which exists precisely so this side can
 * rebuild that string without opening the hot-path file that produces it. That
 * column has no index of its own until V211 adds one.
 */
public interface AgentJobApprovalLookup extends Repository<ApprovalRequest, UUID> {

    /**
     * The blocking-request query, in both directions the sweep needs it: {@code
     * PENDING} answers "is a human still deciding?", {@code REJECTED} answers
     * "did a human say no?".
     *
     * <p>Ascending by creation so the OLDEST outstanding gate is the one a job
     * is reported as parked on - a turn that tripped two gates parked because of
     * the first one, and naming the second in the log would send an operator to
     * the wrong request.
     */
    List<ApprovalRequest> findByRequestedByAndStatusOrderByCreatedAtAsc(String requestedBy, ApprovalStatus status);

    /**
     * Every recent request for this job's label, newest first, whatever its
     * status.
     *
     * <p>Needed because two of the resume decisions cannot be expressed as a
     * status query at all:
     *
     * <ul>
     *   <li><b>{@code EXECUTION_FAILED} is overloaded.</b> With {@code
     *       nextRetryAt} set it means "failed, RETRY SCHEDULED", and {@code
     *       RetryFailedActionsJob} will call the executor again later. Resuming
     *       the job while that retry is still pending means the gated,
     *       non-idempotent tool can run twice - once from the retry, once from
     *       the resumed turn's model deciding to call it again after reading
     *       {@code [approved action '...' failed: ...]} in session memory.</li>
     *   <li><b>Approval commits before the replay runs.</b> {@code
     *       HumanApprovalGateService#approve} persists {@code recordApproved} in
     *       its own transaction and only THEN invokes the executor, outside any
     *       transaction, on the deciding admin's HTTP thread. A sweep tick
     *       landing in that window sees no PENDING and no REJECTED row and would
     *       dispatch before {@code AgentToolCallApprovalExecutor} has replayed
     *       the tool and written its result into session memory - which is the
     *       entire reason a fresh turn continues instead of re-requesting the
     *       same gate. {@code executionResult}/{@code decidedAt} are what let
     *       the sweep wait that window out.</li>
     * </ul>
     *
     * <p>Bounded at 20: a job that tripped more than twenty gates has other
     * problems, and this runs per parked row on a sweep tick.
     */
    List<ApprovalRequest> findTop20ByRequestedByOrderByCreatedAtDesc(String requestedBy);
}
