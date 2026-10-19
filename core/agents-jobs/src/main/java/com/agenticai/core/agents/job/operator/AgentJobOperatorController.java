package com.agenticai.core.agents.job.operator;

import com.agenticai.core.agents.job.operator.dto.AgentJobCountsResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator dashboard's summary line.
 *
 * <p>Sits at {@code /api/agent-jobs/counts}, directly beneath the tenant-wide
 * list at {@code /api/agent-jobs}, and takes the same {@code agentId} query
 * parameter with the same meaning - the two are read together by one screen and
 * a client should not have to learn two different shapes to ask about the same
 * scope.
 *
 * <p><b>A query parameter, not {@code /api/agents/{agentId}/jobs/counts}.</b>
 * That path would sit next to the foundation's {@code
 * /api/agents/{agentId}/jobs/{jobId}}, where {@code jobId} is typed {@code
 * UUID}; the literal segment does win over the templated one under Spring's
 * pattern ordering, but the whole route would then rest on a precedence rule,
 * and getting it wrong surfaces as a 400 about an unparseable UUID rather than
 * anything resembling the actual mistake. This shape has no such dependency.
 *
 * <p>No security matcher is added for this route. It falls through to {@code
 * anyRequest().authenticated()} and does its real authorization in the service -
 * per-agent VIEWER via {@code requireAccess}, and otherwise counts narrowed to
 * the agents the caller can already see. The endpoint is therefore safe for any
 * authenticated user precisely BECAUSE the scope moves with the caller, which is
 * the same read-open posture the job list itself takes.
 */
@RestController
public class AgentJobOperatorController {

    private final AgentJobOperatorService operatorService;

    public AgentJobOperatorController(AgentJobOperatorService operatorService) {
        this.operatorService = operatorService;
    }

    /**
     * Live counts (all-time) plus terminal counts over a lookback window.
     *
     * <p>{@code window} is an ISO-8601 duration - {@code PT1H}, {@code PT24H},
     * {@code P7D} - clamped to [1 minute, 30 days] and echoed back in the
     * response so a clamped request is visible to the client rather than
     * silently changing what its numbers mean. Omit it for 24 hours.
     *
     * <p>{@code agentId} is optional and narrows every count to that one agent,
     * matching {@code GET /api/agent-jobs?agentId=}. Absent, the counts cover
     * the whole tenant for an admin and the caller's visible agents for everyone
     * else; {@code tenantWide} on the response says which of those happened, so
     * a zero is never ambiguous.
     */
    @GetMapping("/api/agent-jobs/counts")
    public AgentJobCountsResponse counts(
        @RequestParam(required = false) UUID agentId,
        @RequestParam(required = false) Duration window
    ) {
        return operatorService.counts(agentId, window);
    }
}
