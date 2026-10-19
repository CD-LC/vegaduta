package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-call LLM usage for ONE agent chat session - the backend source the
 * agent workbench playground's per-turn metrics row never had (see
 * AgentPlayground.tsx's header: the design's cost figure was deliberately
 * omitted rather than fabricated). The console diffs this ascending list
 * against the count it saw before its turn started, which is what turns a
 * session-level ledger into honest per-turn numbers - no new persistence,
 * the data was already written per call.
 *
 * <p>Rows come from {@link LlmCallLogService#forTask}, which reads the
 * tenant schema's {@code llm_call_log} - tenant isolation is the schema
 * itself, so a caller can only ever see their own tenant's calls, and the
 * agentId filter below additionally scopes the session's rows to the agent
 * in the path (a session id belonging to a different agent returns empty,
 * not someone else's numbers).
 *
 * <p>Deliberately a thin, non-admin sibling of {@link
 * LlmCallLogController#callsForTask}: that endpoint is PERM_VIEW_USAGE-gated
 * for the maintenance audience; this one is authenticated like every other
 * {@code /api/agents/**} read so the agent's own author can see what their
 * test turn cost - but it keeps the SAME plan gate ({@link
 * #assertPremiumPlan}): usage/cost visibility is a PREMIUM+ feature in this
 * product's own billing posture, and a workbench endpoint is not a back door
 * around it. FREE tenants get 403 and the playground simply renders the
 * metrics it always had (latency, tool-call count) with cost omitted -
 * status quo, not an error toast.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/llm-usage")
public class AgentSessionLlmUsageController {

    private final LlmCallLogService llmCallLogService;
    private final TenantLimits tenantLimits;

    public AgentSessionLlmUsageController(LlmCallLogService llmCallLogService, TenantLimits tenantLimits) {
        this.llmCallLogService = llmCallLogService;
        this.tenantLimits = tenantLimits;
    }

    /** Same gate and same reasoning as {@link LlmCallLogController#assertPremiumPlan}. */
    private void assertPremiumPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                "LLM usage/tracing (per-call tokens and cost) is a Premium feature - upgrade your plan to view it."
            );
        }
    }

    /**
     * Every LLM call this agent made inside this session, oldest first.
     * Read-only and deliberately NOT edit-locked, matching {@code
     * AgentVersionController#list}'s posture for read surfaces.
     */
    @GetMapping
    public List<SessionLlmCallEntry> calls(@PathVariable UUID agentId, @PathVariable String sessionId) {
        assertPremiumPlan();
        return llmCallLogService.forTask(sessionId).stream()
            .filter(log -> agentId.equals(log.getAgentId()))
            .map(SessionLlmCallEntry::from)
            .toList();
    }

    /**
     * One run's cost, cache-hit ratio and step timeline in a single read - see
     * {@link RunCostTimeline} for what is and is not in it. Same PREMIUM gate and
     * the same agent scoping as {@link #calls}. An unknown session returns an
     * empty summary (zero calls), not 404, matching {@link #calls}.
     */
    @GetMapping("/summary")
    public RunCostTimeline.Summary summary(@PathVariable UUID agentId, @PathVariable String sessionId) {
        assertPremiumPlan();
        List<LlmCallLog> rows = llmCallLogService.forTask(sessionId).stream()
            .filter(log -> agentId.equals(log.getAgentId()))
            .toList();
        return RunCostTimeline.of(sessionId, agentId, rows);
    }

    /**
     * Leaner than the admin's {@code LlmCallLogEntry}: this audience needs
     * "what did that turn cost," not error stack traces - the failure-detail
     * surface stays on the admin endpoint it was built for.
     */
    public record SessionLlmCallEntry(
        UUID id,
        ProviderName providerName,
        String model,
        Long promptTokens,
        Long completionTokens,
        Long totalTokens,
        BigDecimal estimatedCostUsd,
        boolean success,
        long latencyMs,
        Instant startedAt
    ) {
        static SessionLlmCallEntry from(LlmCallLog log) {
            return new SessionLlmCallEntry(
                log.getId(),
                log.getProviderName(),
                log.getModel(),
                log.getPromptTokens(),
                log.getCompletionTokens(),
                log.getTotalTokens(),
                log.getEstimatedCostUsd(),
                log.isSuccess(),
                log.getLatencyMs(),
                log.getStartedAt()
            );
        }
    }
}
