package com.agenticai.core.agents.job.approval;

import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobApprovalPark;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobSession;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Answers the one question {@link AgentJobApprovalPark} exists to ask: did this
 * turn stop because a human approval gate held one of its tool calls, rather
 * than because the job finished its work?
 *
 * <h2>Why a job must PARK and not hold</h2>
 * {@code GuardedToolCallback} records a PENDING {@code approval_request} and
 * returns an explanatory string to the model instead of performing the action.
 * That is exactly right for a chat turn - the human is sitting there. For a job
 * it is not: the turn "completes", produces prose about an action that never
 * happened, and (before this class existed) the job was written SUCCEEDED with
 * that prose as its result. A caller reading {@code result_text} had no way to
 * tell "I did it" from "somebody needs to approve this first". Parking makes
 * that difference a STATUS, which is the whole point of PR-5.
 *
 * <h2>How a gate is detected without touching the guardrail layer</h2>
 * By the {@code requested_by} label. {@code GuardedToolCallback} stamps every
 * request it records with {@code "agent:<agentId>:session:<sessionId>"}, and a
 * job's session id is deterministically {@code "job:<jobId>"} - so the label is
 * reconstructable from the job row alone, which is precisely why {@code
 * AgentJobSession#requestedByLabel} exists and why its unit test asserts the
 * literal format. No edit to {@code GuardedToolCallback}, {@code
 * AgentToolProvider} or {@code AgentChatService} is needed, and none is made.
 *
 * <h2>Two filters, both load-bearing</h2>
 * <ul>
 *   <li><b>{@code status = PENDING}.</b> A request already decided is not
 *       blocking anything.</li>
 *   <li><b>{@code created_at >= turnStartedAt}.</b> Without it, a job that
 *       parked, resumed and then finished cleanly would park AGAIN on the
 *       corpse of its own earlier gate if that gate was never decided - and a
 *       job resumed after an EXECUTION_FAILED replay would park on a request
 *       that no human is ever going to look at again. The turn's own start is
 *       the only honest boundary: a request that predates this turn was not
 *       caused by it.</li>
 * </ul>
 *
 * <h2>This class writes NO status, ever</h2>
 * It returns a decision; {@code AgentJobExecution} performs the durable park
 * through the guarded {@code AgentJobRepository#parkForApproval} UPDATE and then
 * returns WITHOUT {@code finish()}, which is mechanically what releases the
 * worker. One transition, one writer - the invariant the whole guarded-UPDATE
 * design of this package rests on.
 *
 * <p>It also never throws. A failure to DETECT a gate must leave the job to
 * finish normally (an over-reported success), never kill it: this runs on a
 * worker thread that still holds its slot, after the model call has already been
 * paid for.
 */
@Component
public class AgentJobApprovalParkDetector implements AgentJobApprovalPark {

    private static final Logger log = LoggerFactory.getLogger(AgentJobApprovalParkDetector.class);

    /** Detected gates, whether or not the park was ultimately allowed. */
    static final String METRIC_GATED = "agent.job.approval.gated";
    /** Gates refused because the job had already parked its full allowance. */
    static final String METRIC_PARK_LIMIT_REACHED = "agent.job.approval.park_limit_reached";

    private final AgentJobApprovalLookup approvalLookup;
    private final AgentJobProperties properties;
    private final MeterRegistry meterRegistry;

    public AgentJobApprovalParkDetector(
        AgentJobApprovalLookup approvalLookup,
        AgentJobProperties properties,
        MeterRegistry meterRegistry
    ) {
        this.approvalLookup = approvalLookup;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Optional<ParkDecision> parkIfGated(AgentJob job, Instant turnStartedAt) {
        try {
            ApprovalRequest blocking = blockingRequest(job, turnStartedAt);
            if (blocking == null) {
                return Optional.empty();
            }
            meterRegistry.counter(METRIC_GATED).increment();

            Instant now = Instant.now();
            int cap = parkCap();
            if (job.getParkCount() >= cap) {
                // The refusal is expressed AS A PARK with an already-expired
                // deadline rather than as "no decision", and that is deliberate.
                // Returning empty here would let AgentJobExecution finalize and
                // write SUCCEEDED with prose about an action that never ran -
                // exactly the failure this PR exists to remove. Parking with a
                // dead deadline hands the job to the approval sweep, which is
                // the SINGLE place that writes a terminal status for a parked
                // job, and which recognises park_count over the cap and records
                // APPROVAL_PARK_LIMIT rather than APPROVAL_TIMEOUT.
                //
                // Consequence, stated plainly: park_count can exceed the
                // configured ceiling by exactly one. That last over-cap park is
                // what makes the refusal visible and terminable; it never buys
                // the job another turn, because the sweep fails it.
                meterRegistry.counter(METRIC_PARK_LIMIT_REACHED).increment();
                log.warn(
                    "Agent job {} hit a human approval gate for the {}th time, past its ceiling of {} "
                        + "(app.agent-jobs.approval.max-parks-per-job) - it will be failed with APPROVAL_PARK_LIMIT "
                        + "by the approval sweep rather than parked again",
                    job.getId(), job.getParkCount() + 1, cap
                );
                return Optional.of(new ParkDecision(
                    // A whole second in the past, not `now`: the sweep's read is
                    // `approval_deadline_at < :now`, and an equal timestamp
                    // would not match it.
                    now.minusSeconds(1),
                    blocking.getId(),
                    "park limit reached (" + job.getParkCount() + " of " + cap + " parks already used)"
                ));
            }

            return Optional.of(new ParkDecision(
                now.plus(properties.getApproval().getParkTimeout()),
                blocking.getId(),
                "gated action '" + blocking.getActionType() + "' is waiting on a human decision"
            ));

        } catch (RuntimeException e) {
            // Fail OPEN, on purpose. A detection failure that killed the job
            // would turn one transient database blip into a lost job whose model
            // call has already been billed. The cost of failing open is an
            // over-reported success, which the operator can see; the cost of
            // failing closed is work destroyed for a reason unrelated to it.
            log.warn(
                "Could not check agent job {} for a pending approval gate - letting it finish normally", job.getId(), e);
            return Optional.empty();
        }
    }

    /**
     * The oldest PENDING request this turn caused, or {@code null}.
     *
     * <p>Oldest rather than newest: a turn that tripped two gates stopped
     * because of the first one, and naming the second in the log would send an
     * operator to the wrong request.
     */
    private ApprovalRequest blockingRequest(AgentJob job, Instant turnStartedAt) {
        String label = AgentJobSession.requestedByLabel(job.getAgentId(), job.getId());
        List<ApprovalRequest> pending =
            approvalLookup.findByRequestedByAndStatusOrderByCreatedAtAsc(label, ApprovalStatus.PENDING);
        for (ApprovalRequest request : pending) {
            Instant createdAt = request.getCreatedAt();
            if (turnStartedAt == null || (createdAt != null && !createdAt.isBefore(turnStartedAt))) {
                return request;
            }
        }
        return null;
    }

    /**
     * Floored at 1 so a misconfigured {@code 0} cannot mean "never park", which
     * would silently restore the prose-success bug this class removes.
     */
    private int parkCap() {
        return Math.max(1, properties.getApproval().getMaxParksPerJob());
    }
}
