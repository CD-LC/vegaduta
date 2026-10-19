package com.agenticai.core.platform;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.dto.AgentFallbackTotals;
import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditLogRepository;
import com.agenticai.core.audit.IssueStatus;
import com.agenticai.core.audit.PlatformIssue;
import com.agenticai.core.audit.PlatformIssueRepository;
import com.agenticai.core.chat.LlmCallLogRepository;
import com.agenticai.core.chat.LlmOverallUsageSummary;
import com.agenticai.core.chat.LlmProviderUsageSummary;
import com.agenticai.core.platform.PlatformHostedLlmCostSummary.PlatformTenantHostedLlmCost;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform Observability page's backend - direct feedback: "an page in admin platform where I can
 * see the entire tickets, errors, tenants, execution, platform usage, no of calls, llm used and all
 * information and activities done by client." {@code GET /api/platform/tenants} (already built,
 * {@link PlatformTenantMetricsService}) already covers "tenants" and "execution" in the loose sense
 * of agent/workflow counts; this service adds the other three: {@link #listIssues}
 * (tickets/errors), {@link #llmUsageByTenant} (no of calls / llm used / platform usage), and {@link
 * #recentActivity} (activities done by client).
 *
 * <p>Same cross-tenant iteration shape {@link PlatformTenantMetricsService} already established:
 * only {@link TenantStatus#ACTIVE}, non-demo tenants (a PROVISIONING/SUSPENDED tenant's schema may
 * not exist or be migrated yet), {@link TenantContext} set/cleared per tenant in a try/finally so
 * each tenant-scoped repository (routed by Hibernate's schema-per-tenant resolver) sees only that
 * one tenant's rows, and one tenant's query failing never stops the loop or blanks every other
 * tenant's data - logged and skipped, same as that service's per-metric try/catch.
 *
 * <p><b>Known scope boundary, stated honestly rather than silently narrowed:</b> "execution" here
 * does not (yet) include a cross-tenant WorkflowRun/QuickTaskRun history - each tenant's own Run
 * History page already covers that in depth, and folding it in here too would mean a fourth full
 * repository sweep per tenant on every page load. {@link #recentActivity} is the closest proxy
 * available today (audit log entries already cover many workflow/quick-task state changes), not a
 * literal run-by-run list. Likewise "platform usage / no of calls" means real, durable LLM call
 * volume ({@link com.agenticai.core.chat.LlmCallLog}) - there is no raw HTTP-request-count metric
 * anywhere in this codebase to report instead, and inventing one here would be exactly the kind of
 * fabricated number this codebase's own conventions avoid elsewhere.
 */
@Service
@Profile("security")
public class PlatformObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(PlatformObservabilityService.class);
    private static final String SYSTEM_ACTOR = "system:platform-observability";

    // Per-tenant cap before merging - bounds worst-case work/response size
    // when a tenant has an unusually large table, independent of how many
    // tenants exist. The final cross-tenant list is trimmed again after
    // merging (see each method below).
    private static final int PER_TENANT_ROW_CAP = 50;
    private static final int MERGED_ROW_CAP = 200;

    private final TenantRepository tenantRepository;
    private final PlatformIssueRepository platformIssueRepository;
    private final AuditLogRepository auditLogRepository;
    private final LlmCallLogRepository llmCallLogRepository;
    private final AgentRepository agentRepository;
    private final PlatformAlertSettingsRepository platformAlertSettingsRepository;
    private final PlatformSettingsService platformSettingsService;

    // #973 follow-up (dashboard "remaining balance/budget available") - the
    // admin-configured all-time spending line for the PLATFORM_HOSTED pool.
    // Backed by PlatformSettingsService (no migration needed to introduce a
    // new key - see that service's javadoc), same "runtime-config, live on
    // next read" posture BudgetTopUpService's keys already use. Absent by
    // default (null, not a fabricated zero or cap) until an admin sets one.
    static final String HOSTED_LLM_BUDGET_KEY = "platform.llm.hosted-budget-usd";

    public PlatformObservabilityService(
            TenantRepository tenantRepository,
            PlatformIssueRepository platformIssueRepository,
            AuditLogRepository auditLogRepository,
            LlmCallLogRepository llmCallLogRepository,
            AgentRepository agentRepository,
            PlatformAlertSettingsRepository platformAlertSettingsRepository,
            PlatformSettingsService platformSettingsService) {
        this.tenantRepository = tenantRepository;
        this.platformIssueRepository = platformIssueRepository;
        this.auditLogRepository = auditLogRepository;
        this.llmCallLogRepository = llmCallLogRepository;
        this.agentRepository = agentRepository;
        this.platformAlertSettingsRepository = platformAlertSettingsRepository;
        this.platformSettingsService = platformSettingsService;
    }

    /**
     * Tickets/errors across every active tenant, most recently seen first, capped at {@link
     * #MERGED_ROW_CAP}.
     */
    public List<PlatformIssueSummary> listIssues() {
        List<PlatformIssueSummary> merged = new ArrayList<>();
        forEachActiveTenant(
                (tenant) -> {
                    List<PlatformIssue> issues =
                            platformIssueRepository.findTop200ByOrderByLastSeenAtDesc();
                    int limit = Math.min(issues.size(), PER_TENANT_ROW_CAP);
                    for (int i = 0; i < limit; i++) {
                        PlatformIssue issue = issues.get(i);
                        merged.add(
                                new PlatformIssueSummary(
                                        tenant.getId(),
                                        tenant.getName(),
                                        issue.getId(),
                                        issue.getSource(),
                                        issue.getSourceId(),
                                        issue.getSeverity(),
                                        issue.getStatus(),
                                        issue.getTitle(),
                                        issue.getOccurrenceCount(),
                                        issue.getFirstSeenAt(),
                                        issue.getLastSeenAt()));
                    }
                },
                "issues");
        merged.sort(Comparator.comparing(PlatformIssueSummary::lastSeenAt).reversed());
        return merged.size() > MERGED_ROW_CAP ? merged.subList(0, MERGED_ROW_CAP) : merged;
    }

    /**
     * All-time LLM call volume/cost, one row per registered active tenant that has made at least
     * one call (not capped/merged - one row per tenant, not per call).
     */
    public List<PlatformTenantLlmUsageSummary> llmUsageByTenant() {
        List<PlatformTenantLlmUsageSummary> rows = new ArrayList<>();
        forEachActiveTenant(
                (tenant) -> {
                    LlmOverallUsageSummary overall = llmCallLogRepository.overallSummary();
                    if (overall == null || overall.totalCalls() <= 0) {
                        return; // a tenant that never called an LLM is not a usage row
                    }
                    long totalTokens = llmCallLogRepository.tokensSince(Instant.EPOCH);
                    BigDecimal cost = llmCallLogRepository.costSince(Instant.EPOCH);
                    rows.add(
                            new PlatformTenantLlmUsageSummary(
                                    tenant.getId(),
                                    tenant.getName(),
                                    overall.totalCalls(),
                                    overall.errorCount(),
                                    overall.avgLatencyMs(),
                                    totalTokens,
                                    cost == null ? BigDecimal.ZERO : cost));
                },
                "llm usage");
        rows.sort(Comparator.comparingLong(PlatformTenantLlmUsageSummary::totalCalls).reversed());
        return rows;
    }

    /**
     * #973 - "consider the price, charges to get the open source llm from open router or any other
     * platform" - platform-wide totals for the {@code PLATFORM_HOSTED} pool only (the FREE-tier
     * hosted-provider traffic the platform itself pays for, not any tenant's BYOK usage), plus a
     * per-tenant breakdown of which FREE tenants are driving that spend. See {@link
     * PlatformHostedLlmCostSummary}'s javadoc for the full rationale; built the same cross-schema
     * sweep shape as {@link #llmUsageByTenant}, just scoped to one provider via {@link
     * LlmCallLogRepository#summarizeProvider}.
     */
    public PlatformHostedLlmCostSummary platformHostedLlmCostSummary() {
        long[] totals = new long[3]; // [0]=calls, [1]=errors, [2]=tokens
        BigDecimal[] costTotal = {BigDecimal.ZERO};
        List<PlatformTenantHostedLlmCost> byTenant = new ArrayList<>();
        forEachActiveTenant(
                (tenant) -> {
                    LlmProviderUsageSummary summary =
                            llmCallLogRepository.summarizeProvider(ProviderName.PLATFORM_HOSTED);
                    if (summary == null || summary.getTotalCalls() <= 0) {
                        return;
                    }
                    totals[0] += summary.getTotalCalls();
                    totals[1] += summary.getErrorCount();
                    totals[2] += summary.getTotalTokens();
                    BigDecimal callCost =
                            summary.getEstimatedCostUsd() == null
                                    ? BigDecimal.ZERO
                                    : summary.getEstimatedCostUsd();
                    costTotal[0] = costTotal[0].add(callCost);
                    byTenant.add(
                            new PlatformTenantHostedLlmCost(
                                    tenant.getId(),
                                    tenant.getName(),
                                    summary.getTotalCalls(),
                                    summary.getTotalTokens(),
                                    callCost));
                },
                "hosted LLM cost");
        byTenant.sort(
                Comparator.comparing(PlatformTenantHostedLlmCost::estimatedCostUsd).reversed());
        BigDecimal budget = readHostedLlmBudget();
        BigDecimal remaining = budget == null ? null : budget.subtract(costTotal[0]);
        return new PlatformHostedLlmCostSummary(
                totals[0], totals[1], totals[2], costTotal[0], budget, remaining, byTenant);
    }

    /**
     * Day-bucketed {@code PLATFORM_HOSTED}-only totals across every active tenant, from {@code
     * days} ago (inclusive, UTC calendar days) through today - the dashboard's trend line. Same
     * cross-schema sweep shape as {@link #platformHostedLlmCostSummary()}, just merged by day
     * instead of summed into one number; a day is present in the result only if at least one tenant
     * had PLATFORM_HOSTED traffic that day.
     */
    public List<PlatformHostedLlmCostDay> platformHostedLlmCostByDay(int days) {
        Instant from =
                LocalDate.now(ZoneOffset.UTC)
                        .minusDays(Math.max(days, 1) - 1L)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
        Map<LocalDate, long[]> byDay = new TreeMap<>(); // [0]=calls, [1]=tokens
        Map<LocalDate, BigDecimal> costByDay = new TreeMap<>();
        forEachActiveTenant(
                (tenant) -> {
                    List<LlmCallLogRepository.DailyProviderCostRow> rows =
                            llmCallLogRepository.dailyCostForProvider(
                                    ProviderName.PLATFORM_HOSTED.name(), from);
                    for (LlmCallLogRepository.DailyProviderCostRow row : rows) {
                        LocalDate day = row.getUsageDate();
                        long[] acc = byDay.computeIfAbsent(day, d -> new long[2]);
                        acc[0] += row.getTotalCalls();
                        acc[1] += row.getTotalTokens();
                        BigDecimal cost =
                                row.getEstimatedCostUsd() == null
                                        ? BigDecimal.ZERO
                                        : row.getEstimatedCostUsd();
                        costByDay.merge(day, cost, BigDecimal::add);
                    }
                },
                "hosted LLM cost by day");

        List<PlatformHostedLlmCostDay> result = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> entry : byDay.entrySet()) {
            LocalDate day = entry.getKey();
            result.add(
                    new PlatformHostedLlmCostDay(
                            day,
                            entry.getValue()[0],
                            entry.getValue()[1],
                            costByDay.getOrDefault(day, BigDecimal.ZERO)));
        }
        return result;
    }

    /**
     * Sets (or clears, when {@code budgetUsd} is {@code null}) the platform's all-time hosted-LLM
     * budget line and returns the recomputed summary so the admin PUT endpoint can render
     * "remaining balance" immediately.
     */
    @Transactional
    public PlatformHostedLlmCostSummary updateHostedLlmBudget(
            BigDecimal budgetUsd, String updatedBy) {
        if (budgetUsd != null && budgetUsd.signum() < 0) {
            throw new IllegalArgumentException("Platform hosted-LLM budget cannot be negative");
        }
        platformSettingsService.update(
                HOSTED_LLM_BUDGET_KEY,
                budgetUsd == null ? "" : budgetUsd.toPlainString(),
                updatedBy);
        return platformHostedLlmCostSummary();
    }

    private BigDecimal readHostedLlmBudget() {
        String raw = platformSettingsService.getString(HOSTED_LLM_BUDGET_KEY, null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn(
                    "Platform setting '{}' has a non-numeric value '{}' - treating hosted-LLM"
                            + " budget as unconfigured",
                    HOSTED_LLM_BUDGET_KEY,
                    raw);
            return null;
        }
    }

    /**
     * {@code GET /api/platform/issues/open-count} - the overview tile's figure. Counts {@link
     * IssueStatus#OPEN} issues in every registered active tenant exactly (a COUNT per schema),
     * unlike {@link #listIssues}, which is a capped, all-status recent-activity list and
     * undercounts as soon as any tenant has more than {@link #PER_TENANT_ROW_CAP} issues. {@code
     * failedTenants} is how many tenant schemas could not be counted, so a partial figure is never
     * presented as whole.
     */
    public OpenIssueCount openIssueCount() {
        long[] open = {0};
        int[] failed = {0};
        for (Tenant tenant : tenantRepository.findActiveExcludingDemo()) {
            try {
                TenantContext.set(TenantContext.of(tenant.getSchemaName(), SYSTEM_ACTOR, Set.of()));
                open[0] += platformIssueRepository.countByStatus(IssueStatus.OPEN);
            } catch (RuntimeException e) {
                failed[0]++;
                log.warn(
                        "Failed to count open issues for tenant schema '{}'",
                        tenant.getSchemaName(),
                        e);
            } finally {
                TenantContext.clear();
            }
        }
        return new OpenIssueCount(open[0], failed[0]);
    }

    public record OpenIssueCount(long open, int failedTenants) {}

    /**
     * Recent state-changing activity across every active tenant, newest first, capped at {@link
     * #MERGED_ROW_CAP}.
     */
    public List<PlatformActivityEntry> recentActivity() {
        List<PlatformActivityEntry> merged = new ArrayList<>();
        forEachActiveTenant(
                (tenant) -> {
                    List<AuditLogEntry> entries =
                            auditLogRepository.findTop200ByOrderByCreatedAtDesc();
                    int limit = Math.min(entries.size(), PER_TENANT_ROW_CAP);
                    for (int i = 0; i < limit; i++) {
                        AuditLogEntry entry = entries.get(i);
                        merged.add(
                                new PlatformActivityEntry(
                                        tenant.getId(),
                                        tenant.getName(),
                                        entry.getId(),
                                        entry.getActorId(),
                                        entry.getActionType(),
                                        entry.getTargetType(),
                                        entry.getTargetId(),
                                        entry.getOutcome(),
                                        entry.getDetail(),
                                        entry.getCreatedAt()));
                    }
                },
                "activity");
        merged.sort(Comparator.comparing(PlatformActivityEntry::createdAt).reversed());
        return merged.size() > MERGED_ROW_CAP ? merged.subList(0, MERGED_ROW_CAP) : merged;
    }

    /**
     * Platform-admin alert threshold follow-up (#956) - lifetime fallback totals ({@link
     * com.agenticai.core.agents.core.Agent#getFallbackTriggeredCount}/ {@code getFallbackFailedCount},
     * summed via {@link AgentRepository#sumFallbackCounts()}) across every active tenant's agents,
     * plus a breakdown of only the tenants that have ever had a fallback fire (see {@link
     * PlatformTenantFallbackSummary}'s javadoc), compared against the configured {@link
     * PlatformAlertSettings#getFallbackFailureThreshold()}.
     */
    public PlatformFallbackAlertStatus computeFallbackAlertStatus() {
        long[] totals = new long[2]; // [0]=triggered, [1]=failed - accumulated across tenants below
        List<PlatformTenantFallbackSummary> breakdown = new ArrayList<>();
        forEachActiveTenant(
                (tenant) -> {
                    AgentFallbackTotals tenantTotals = agentRepository.sumFallbackCounts();
                    totals[0] += tenantTotals.triggeredTotal();
                    totals[1] += tenantTotals.failedTotal();
                    if (tenantTotals.triggeredTotal() > 0 || tenantTotals.failedTotal() > 0) {
                        breakdown.add(
                                new PlatformTenantFallbackSummary(
                                        tenant.getId(),
                                        tenant.getName(),
                                        tenantTotals.triggeredTotal(),
                                        tenantTotals.failedTotal()));
                    }
                },
                "fallback totals");
        breakdown.sort(
                Comparator.comparingLong(PlatformTenantFallbackSummary::fallbackFailedCount)
                        .reversed());
        int threshold = getFallbackFailureThreshold();
        return new PlatformFallbackAlertStatus(
                threshold, totals[0], totals[1], totals[1] >= threshold, breakdown);
    }

    /**
     * Current fleet-wide fallback-failure alert threshold - see {@link PlatformAlertSettings}'s
     * javadoc for why there's only one.
     */
    public int getFallbackFailureThreshold() {
        return loadOrCreateAlertSettings().getFallbackFailureThreshold();
    }

    /**
     * Updates the threshold, then recomputes so the caller (the admin PUT endpoint) can render the
     * effect immediately.
     */
    @Transactional
    public PlatformFallbackAlertStatus updateFallbackFailureThreshold(
            int newThreshold, String updatedBy) {
        if (newThreshold < 0) {
            throw new IllegalArgumentException("Fallback failure threshold cannot be negative");
        }
        PlatformAlertSettings settings = loadOrCreateAlertSettings();
        settings.setFallbackFailureThreshold(newThreshold, updatedBy);
        platformAlertSettingsRepository.save(settings);
        return computeFallbackAlertStatus();
    }

    /**
     * Migration V84 always seeds the singleton row - this fallback only matters if that row was
     * somehow deleted.
     */
    private PlatformAlertSettings loadOrCreateAlertSettings() {
        return platformAlertSettingsRepository
                .findById(PlatformAlertSettings.SINGLETON_ID)
                .orElseGet(
                        () ->
                                platformAlertSettingsRepository.save(
                                        new PlatformAlertSettings(
                                                PlatformAlertSettings
                                                        .DEFAULT_FALLBACK_FAILURE_THRESHOLD)));
    }

    /**
     * Shared iteration shell - same {@code TenantContext.set}/{@code clear()} try/finally {@link
     * PlatformTenantMetricsService} uses, with the per-tenant work handed off as a callback so each
     * of the three methods above only has to say what to collect, not how to safely iterate. {@code
     * what} is purely for the warning log line's context.
     */
    private void forEachActiveTenant(
            java.util.function.Consumer<Tenant> perTenantWork, String what) {
        // Registered tenants only, oldest first (2026-10-09): anonymous /try
        // sandboxes are ACTIVE tenants too, and sweeping them put landing-page
        // traffic into every figure on the observability page.
        for (Tenant tenant : tenantRepository.findActiveExcludingDemo()) {
            try {
                TenantContext.set(TenantContext.of(tenant.getSchemaName(), SYSTEM_ACTOR, Set.of()));
                try {
                    perTenantWork.accept(tenant);
                } catch (RuntimeException e) {
                    log.warn(
                            "Failed to collect {} for tenant schema '{}'",
                            what,
                            tenant.getSchemaName(),
                            e);
                }
            } finally {
                TenantContext.clear();
            }
        }
    }
}
