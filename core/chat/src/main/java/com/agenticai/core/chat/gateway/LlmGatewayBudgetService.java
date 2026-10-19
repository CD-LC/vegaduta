package com.agenticai.core.chat.gateway;

import com.agenticai.core.chat.LlmSpendReader;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * #848 (LLM gateway seam) - "central cost/usage governance," a monthly USD spend guardrail checked
 * before a call goes out, using the same {@code llm_call_log} table {@code LlmCallLogService}'s
 * Dashboard readouts already read from (this platform is schema-per-tenant, so {@link
 * LlmSpendReader#costSince} is already implicitly scoped to the calling tenant - no extra WHERE
 * clause needed here).
 *
 * <p>This is a BYOK-spend guardrail, not a platform-cost-protection measure: PREMIUM/UNLIMITED
 * tenants pay their own provider bill directly (see {@code ProviderCredentialResolver}), so the
 * platform has no margin at risk here. The cap exists to protect a TENANT from their own runaway
 * agent (a bad loop, a misconfigured workflow re-triggering itself, etc.) racking up spend on the
 * tenant's own key before anyone notices - same "fail-safe, not a punishment" spirit as the
 * platform/tenant kill switch (#834). {@code UNLIMITED} tenants get no cap at all, matching {@link
 * TenantPlan}'s own javadoc ("no caps, white-label option"). {@code FREE} tenants never actually
 * reach this check in practice (they're routed to the hosted pool via {@code
 * FreeTierChatClientService} before {@link LlmGatewayService} is ever called - see {@code
 * AgentChatService#buildChatClientFor}), but still get a real (zero-default) cap here as a
 * defensive backstop in case a future call site ever forgets that branch, rather than silently
 * allowing unbounded BYOK spend for a plan that isn't supposed to have BYOK at all.
 *
 * <p>Per-plan flat defaults, not yet a per-tenant-configurable override - that's the natural next
 * increment (a column on {@code Tenant} or a new settings row, surfaced in Platform Maintenance)
 * once a real tenant asks for a different cap than the plan default; not built speculatively here.
 */
@Component
public class LlmGatewayBudgetService {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayBudgetService.class);

    // Infra audit (2026-07-27) - shared rejection-counter metric name across
    // every gateway/limiter service in this codebase (see
    // LlmGatewayRateLimiterService/FreeTierUsageLimiterService's identical
    // constant), tagged tenant + reason rather than a separate metric per
    // service - one dashboard panel/alert then covers "why are calls being
    // rejected" regardless of which check tripped.
    static final String REJECTIONS_METRIC = "llm.gateway.rejections";
    private static final String BUDGET_EXCEEDED_REASON = "budget_exceeded";

    private final LlmSpendReader spendReader;
    private final TenantLimits tenantLimits;
    private final BigDecimal premiumMonthlyCapUsd;
    private final BigDecimal freeMonthlyCapUsd;
    private final MeterRegistry meterRegistry;

    public LlmGatewayBudgetService(
            LlmSpendReader spendReader,
            TenantLimits tenantLimits,
            @Value("${app.llm.gateway.budget.premium-monthly-cap-usd:200}")
                    String premiumMonthlyCapUsd,
            @Value("${app.llm.gateway.budget.free-monthly-cap-usd:0}") String freeMonthlyCapUsd,
            MeterRegistry meterRegistry) {
        this.spendReader = spendReader;
        this.tenantLimits = tenantLimits;
        this.premiumMonthlyCapUsd = new BigDecimal(premiumMonthlyCapUsd);
        this.freeMonthlyCapUsd = new BigDecimal(freeMonthlyCapUsd);
        this.meterRegistry = meterRegistry;
    }

    /**
     * @throws LlmGatewayLimitExceededException if this tenant's plan has a monthly cap and this
     *     tenant has already reached or exceeded it. {@code tenantId} null skips the check
     *     (internal/system call, no tenant to bill against). Fails OPEN (logs and lets the call
     *     through) on any lookup error - a missing budget check must never be the reason a real
     *     chat turn fails, same posture every other "best-effort governance" seam in this codebase
     *     already takes (see {@code AgentMemoryService} recall failures, {@code
     *     ToolCircuitBreakerService}'s Redis-unreachable fail-open).
     */
    @Transactional(readOnly = true)
    public void assertWithinBudget(String tenantId) {
        assertWithinBudget(tenantId, null);
    }

    /**
     * As {@link #assertWithinBudget(String)} for a caller that already resolved the tenant's
     * effective plan for this turn: {@code knownPlan} is used as is, so the check costs no plan
     * lookup of its own. {@code null} reads the plan.
     */
    @Transactional(readOnly = true)
    public void assertWithinBudget(String tenantId, TenantPlan knownPlan) {
        if (tenantId == null) {
            return;
        }
        try {
            Optional<BigDecimal> cap =
                    capFor(knownPlan != null ? knownPlan : tenantLimits.plan(tenantId));
            if (cap.isEmpty()) {
                return;
            }
            LocalDate monthStart =
                    Instant.now().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
            // O(1) primary-key read of the counter LlmCallLogService maintains (V481). A month
            // with no row yet (first call of the month, or a counter never written), or a counter
            // table that cannot be read (V481 not applied), falls back to the SUM it replaced, so
            // the answer is the same either way. LlmSpendReader reads behind a savepoint: a
            // missing table cannot mark this (shared) transaction rollback-only.
            BigDecimal spentThisMonth = spendReader.monthlySpend(monthStart);
            if (spentThisMonth == null) {
                spentThisMonth =
                        spendReader.costSince(monthStart.atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            if (spentThisMonth != null && spentThisMonth.compareTo(cap.get()) >= 0) {
                meterRegistry
                        .counter(
                                REJECTIONS_METRIC,
                                "tenant",
                                tenantId,
                                "reason",
                                BUDGET_EXCEEDED_REASON)
                        .increment();
                throw new LlmGatewayLimitExceededException(
                        "This tenant's monthly LLM spend cap ($"
                                + cap.get()
                                + ") has been reached "
                                + "($"
                                + spentThisMonth
                                + " spent so far this month) - contact your admin to raise it.");
            }
        } catch (LlmGatewayLimitExceededException limitExceeded) {
            throw limitExceeded;
        } catch (RuntimeException e) {
            log.warn(
                    "LLM gateway budget check failed for tenant {} - failing open (allowing the"
                            + " call)",
                    tenantId,
                    e);
        }
    }

    /**
     * The monthly cap {@link #assertWithinBudget} enforces for this tenant's plan, or empty when
     * the plan has none. Exposed so a read-only view (the ROI page's budget bar) reports the same
     * limit that is enforced instead of re-deriving it.
     */
    public Optional<BigDecimal> monthlyCapUsd(String tenantId) {
        return capFor(tenantLimits.plan(tenantId));
    }

    private Optional<BigDecimal> capFor(TenantPlan plan) {
        return switch (plan) {
            case UNLIMITED -> Optional.empty();
            case PREMIUM -> Optional.of(premiumMonthlyCapUsd);
            case FREE -> Optional.of(freeMonthlyCapUsd);
        };
    }
}
