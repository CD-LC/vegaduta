package com.agenticai.core.agents.job.sentinel;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.approval.ApprovalActionExecutor;
import com.agenticai.core.approval.ApprovalRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Turns an approved {@code agent.proposal} request into a real agent job -
 * the "humans ratify" half of ambient mode. {@code AgentSentinelSweep} parks a
 * sentinel's {@code propose} verdict as an {@link ApprovalRequest}; this bean
 * is what runs when a human clicks approve on it.
 *
 * <p>Registered like every other gated action: one bean implementing {@link
 * ApprovalActionExecutor}, picked up by {@code ApprovalActionExecutorRegistry}
 * - adding the action type needed no change to {@code HumanApprovalGateService}
 * (see that interface's javadoc).
 *
 * <p><b>Identity rule.</b> The job runs as the human who DECIDED, not the
 * sentinel that proposed and not a system identity - the same invariant {@code
 * AgentJobLaunch}'s javadoc states for schedules. The one exception is an
 * auto-approved request (a tenant loosened {@code agent.proposal} in its
 * approval policy): its {@code decidedBy} is the {@code "agent:...:sentinel:
 * ..."} label the sweep wrote, which is not a human and cannot own spend, so
 * the job falls back to the schedule's {@code configuredBy} - the human whose
 * authority every firing already runs with, captured into the payload at
 * proposal time because the sweep thread's {@code TenantContext} is long gone
 * by the time a human clicks approve.
 */
@Component
public class SentinelProposalExecutor implements ApprovalActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(SentinelProposalExecutor.class);

    /** The action type - added to {@code ApprovalPolicyService}'s default-sensitive patterns so a proposal is born PENDING. */
    public static final String ACTION_TYPE = "agent.proposal";

    private static final int MAX_GOAL = 4000;

    private final ObjectProvider<AgentJobLauncher> launcher;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public SentinelProposalExecutor(
        ObjectProvider<AgentJobLauncher> launcher,
        AgentRepository agentRepository,
        ObjectMapper objectMapper
    ) {
        this.launcher = launcher;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public String actionType() {
        return ACTION_TYPE;
    }

    /**
     * Enqueues the proposed run. Throws on an unusable payload or a dead agent -
     * {@code HumanApprovalGateService} records that as EXECUTION_FAILED so the
     * approver sees "approved but could not run: <reason>" rather than a
     * silently dropped job.
     */
    @Override
    public void execute(ApprovalRequest request) {
        JsonNode payload = parse(request);
        UUID agentId = UUID.fromString(payload.path("agentId").asText());
        String goal = payload.path("goal").asText("").trim();
        if (goal.isEmpty()) {
            throw new IllegalStateException(
                "agent.proposal request " + request.getId() + " has no goal to run");
        }
        if (goal.length() > MAX_GOAL) {
            goal = goal.substring(0, MAX_GOAL);
        }

        if (agentRepository.findById(agentId).isEmpty()) {
            throw new IllegalStateException(
                "agent.proposal request " + request.getId() + " names agent " + agentId
                    + ", which no longer exists");
        }

        String runAs = request.getDecidedBy();
        if (runAs == null || runAs.isBlank() || runAs.startsWith("agent:")) {
            runAs = payload.path("configuredBy").asText(null);
        }
        if (runAs == null || runAs.isBlank()) {
            throw new IllegalStateException(
                "agent.proposal request " + request.getId()
                    + " has no human identity to run as (decidedBy and configuredBy are both unusable)");
        }

        UUID jobId = launcher.getObject().enqueue(new AgentJobLaunch(
            agentId,
            goal,
            AgentJobOrigin.APPROVAL,
            "approval:" + request.getId(),
            null,               // no schedule binding: the proposal is one-off work, not an occurrence
            Instant.now(),
            runAs,
            // No roles snapshot exists on the proposal, same posture as a
            // schedule firing: fewer roles can only make the job less capable
            // than the human who ratified it, never more.
            Set.of(),
            runAs,
            null));
        request.recordExecutionResult("Enqueued agent job " + jobId + " as " + runAs);
        log.info("agent.proposal {} approved by {} -> agent job {} (agent {})",
            request.getId(), request.getDecidedBy(), jobId, agentId);
    }

    private JsonNode parse(ApprovalRequest request) {
        try {
            JsonNode payload = objectMapper.readTree(request.getPayloadJson() == null ? "" : request.getPayloadJson());
            if (!payload.path("agentId").isTextual()) {
                throw new IllegalStateException("missing agentId");
            }
            return payload;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                "agent.proposal request " + request.getId() + " has an unparseable payload", e);
        }
    }
}
