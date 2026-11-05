package com.agenticai.core.agents.memorystore.decay;

import java.math.BigDecimal;

/**
 * Guard in front of the only step of consolidation that costs money (the summariser model call,
 * design 03 section 4 "Cost budgets"). The steps that are free (score, forget, merge, graph sync)
 * never ask it.
 *
 * <p>Why a port and not {@code SpendCapService} directly: {@code SpendCapService} and {@code
 * PerUserBudgetService} live in {@code agents-budget}, which drags JPA, Redis and the provider
 * stack into a module that is otherwise pure. The sweep in {@code agents-jobs} (which already sees
 * both) supplies a {@link CapProbe} adapter: {@code withinTenantCap} wraps {@code
 * SpendCapService.assertWithinCap(schema)} (false on {@code SpendCapReachedException}) and {@code
 * withinSubjectCap} wraps {@code PerUserBudgetService.checkBudget}, using the synthetic principal
 * {@code system:memory-consolidation} for subjects without a platform user. {@link
 * CeilingConsolidationBudget} adds the job-level USD ceilings on top. Calls still go through the
 * normal provider path so {@code llm_call_log} and {@code onHostedSpend} fire.
 */
public interface ConsolidationBudget {

    /** Outcome of asking to spend. Anything but {@link #ALLOWED} means skip the model step. */
    enum Decision {
        ALLOWED,
        /**
         * Tenant hosted-spend cap reached: skip model work for the tenant tonight (BUDGET_SKIPPED).
         */
        TENANT_CAP_REACHED,
        /** Per-user cap reached: skip this subject only. */
        SUBJECT_CAP_REACHED,
        /** {@code max-usd-per-tenant-per-night} would be exceeded. */
        TENANT_NIGHT_CEILING,
        /** {@code max-usd-per-run} would be exceeded. */
        RUN_CEILING
    }

    /**
     * Ask before one model call whose cost is at most {@code estimatedUsd}. Reserves on ALLOWED.
     */
    Decision tryReserve(String tenantSchema, String subjectKey, BigDecimal estimatedUsd);

    /**
     * After the call: replace the reservation with the actual cost (null or negative: release it).
     */
    void settle(String tenantSchema, BigDecimal reservedUsd, BigDecimal actualUsd);

    /** The existing cap services, seen through the two questions the budget needs. */
    interface CapProbe {
        boolean withinTenantCap(String tenantSchema);

        boolean withinSubjectCap(String tenantSchema, String subjectKey);
    }
}
