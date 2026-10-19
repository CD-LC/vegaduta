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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Direct feedback: "there should be an option to see the llm errors and
 * success logs and also, customer should be able to evaluate the price
 * cost per call or a task, and an outstanding balance of activated llm."
 * {@code /api/admin/**} is already tenant-admin-gated (see {@code
 * SecurityConfig}), matching every other Platform Maintenance readout this
 * pairs with (system status, audit log, users/approvals).
 *
 * <p>Billing audit (2026-07-27): call-log/cost-per-call/cost-per-task viewing
 * had no plan gate at all - every tenant, FREE included, could hit these
 * endpoints. Matches the industry pattern (LangSmith/Relevance AI gate
 * eval/observability access to paid tiers) - now PREMIUM+ only, same
 * gate posture as {@code AgentService#updateModel}/{@code AgentService#create}'s
 * FREE-tier checks: {@link TenantLimitExceededException}, mapped to HTTP 403
 * by {@code GlobalExceptionHandler}. PREMIUM/UNLIMITED tenants are unaffected.
 */
@RestController
@RequestMapping("/api/admin/llm-usage")
public class LlmCallLogController {

    private final LlmCallLogService llmCallLogService;
    private final LlmCallDiagnosisService llmCallDiagnosisService;
    private final TenantLimits tenantLimits;

    public LlmCallLogController(
        LlmCallLogService llmCallLogService,
        LlmCallDiagnosisService llmCallDiagnosisService,
        TenantLimits tenantLimits
    ) {
        this.llmCallLogService = llmCallLogService;
        this.llmCallDiagnosisService = llmCallDiagnosisService;
        this.tenantLimits = tenantLimits;
    }

    /**
     * FREE-tier gate for LLM usage/tracing visibility - see this class's
     * javadoc. tenantId can be null for callers with no HTTP request context
     * (none exist today for this admin-only surface, but this guards against
     * silently skipping the check rather than NPE-ing); such callers are
     * treated as UNLIMITED, same posture as {@code AgentService}.
     */
    private void assertPremiumPlan() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                "LLM usage/tracing (call logs, cost per call/task) is a Premium feature - "
                    + "upgrade your plan to view it."
            );
        }
    }

    /** Recent call log, newest first - the success/error log viewer. {@code status}: {@code all} (default), {@code success}, or {@code error}. */
    @GetMapping("/calls")
    public List<LlmCallLogEntry> recentCalls(
        @RequestParam(defaultValue = "50") int limit,
        @RequestParam(defaultValue = "all") String status
    ) {
        assertPremiumPlan();
        Boolean successFilter = switch (status.toLowerCase(java.util.Locale.ROOT)) {
            case "success" -> Boolean.TRUE;
            case "error" -> Boolean.FALSE;
            default -> null;
        };
        int bounded = Math.max(1, Math.min(limit, 200));
        return llmCallLogService.recent(bounded, successFilter).stream().map(LlmCallLogEntry::from).toList();
    }

    /** Every call sharing one task/session id, in order - "cost per task." */
    @GetMapping("/tasks/{sessionId}")
    public List<LlmCallLogEntry> callsForTask(@org.springframework.web.bind.annotation.PathVariable String sessionId) {
        assertPremiumPlan();
        return llmCallLogService.forTask(sessionId).stream().map(LlmCallLogEntry::from).toList();
    }

    /** One row per provider actually used - "outstanding balance of activated llm" (an ESTIMATE, see {@link LlmPricingCatalog}'s javadoc). */
    @GetMapping("/summary")
    public List<LlmProviderUsageSummaryEntry> summary() {
        assertPremiumPlan();
        return llmCallLogService.usageSummaryByProvider().stream().map(LlmProviderUsageSummaryEntry::from).toList();
    }

    /**
     * Structured diagnosis of one FAILED call - deterministic rules first,
     * the Smart-AI-governed LLM fallback only for unrecognized errors (see
     * {@link LlmCallDiagnosisService}'s javadoc for the full contract). POST
     * (not GET) because the fallback path can spend real platform money on
     * an LLM call - not something a prefetching proxy should trigger. 404
     * when the row doesn't exist in this tenant's schema OR the call
     * succeeded (nothing to diagnose); same PREMIUM+ gate as every other
     * usage/tracing readout here.
     */
    @org.springframework.web.bind.annotation.PostMapping("/calls/{id}/diagnose")
    public LlmCallDiagnosisEntry diagnoseCall(@org.springframework.web.bind.annotation.PathVariable UUID id) {
        assertPremiumPlan();
        return llmCallDiagnosisService.diagnoseCall(id)
            .map(LlmCallDiagnosisEntry::from)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,
                "No failed LLM call found with id " + id + " - diagnosis applies to failed calls only."
            ));
    }

    public record LlmCallLogEntry(
        UUID id, UUID agentId, String sessionId, ProviderName providerName, String model,
        Long promptTokens, Long completionTokens, Long totalTokens, BigDecimal estimatedCostUsd,
        boolean success, String errorMessage, long latencyMs, Instant startedAt
    ) {
        static LlmCallLogEntry from(LlmCallLog log) {
            return new LlmCallLogEntry(
                log.getId(), log.getAgentId(), log.getSessionId(), log.getProviderName(), log.getModel(),
                log.getPromptTokens(), log.getCompletionTokens(), log.getTotalTokens(), log.getEstimatedCostUsd(),
                log.isSuccess(), log.getErrorMessage(), log.getLatencyMs(), log.getStartedAt()
            );
        }
    }

    public record LlmCallDiagnosisEntry(
        String category, String title, String explanation, String suggestedFix,
        List<String> suggestedModels, String source
    ) {
        static LlmCallDiagnosisEntry from(LlmCallDiagnosisService.DiagnosisResult result) {
            return new LlmCallDiagnosisEntry(
                result.category(), result.title(), result.explanation(), result.suggestedFix(),
                result.suggestedModels(), result.source()
            );
        }
    }

    public record LlmProviderUsageSummaryEntry(
        ProviderName providerName, long totalCalls, long errorCount, long totalTokens,
        BigDecimal estimatedCostUsd, Instant firstCallAt, Instant lastCallAt
    ) {
        static LlmProviderUsageSummaryEntry from(LlmProviderUsageSummary summary) {
            return new LlmProviderUsageSummaryEntry(
                summary.getProviderName(), summary.getTotalCalls(), summary.getErrorCount(), summary.getTotalTokens(),
                summary.getEstimatedCostUsd(), summary.getFirstCallAt(), summary.getLastCallAt()
            );
        }
    }
}
