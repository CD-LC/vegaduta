package com.agenticai.core.agents.budget;

import com.agenticai.core.security.TenantLimitExceededException;
import java.math.BigDecimal;

/**
 * PLATFORM-ADMIN-GOVERNANCE-AND-CONTROLS.md §4 (Budgets &amp; Limits) Phase P2
 * (paid top-up / overage) - the distinct "pay to continue" signal thrown by
 * {@link PerUserBudgetService#checkBudget} when a (tenant, user, modality)
 * window's spend has reached its effective cap (base per-user dollar cap plus
 * any paid top-up credit for the current window).
 *
 * <p><b>Why a subclass of {@link TenantLimitExceededException} rather than a
 * standalone type.</b> P1 already threw {@code TenantLimitExceededException}
 * from this exact gate, and every existing catch-site / test treats a
 * per-user-budget rejection as an instance of that type. Extending it keeps
 * all of that working unchanged, while giving {@code GlobalExceptionHandler}
 * a strictly more specific type to bind a dedicated handler to: Spring's
 * {@code @ExceptionHandler} resolution picks the most specific match, so the
 * per-tenant seat/agent ceilings that still throw the plain superclass keep
 * their HTTP 403 "upgrade your plan" mapping, while THIS type maps to HTTP 402
 * Payment Required with a structured "top up to continue" body. The extra
 * fields ({@code modality}/{@code capUsd}/{@code spentUsd}/{@code
 * resetSeconds}) are what that 402 body is built from - the per-tenant caps
 * carry none of them and stay a flat 403.
 *
 * <p>Unlike the per-tenant caps, this ceiling is <em>payable</em>: a
 * successful top-up ({@link PerUserBudgetService#creditTopUp}) raises the
 * effective cap for the current window, so the very next turn passes the gate
 * without any plan change.
 */
public class PerUserBudgetExhaustedException extends TenantLimitExceededException {

    private final String modality;
    private final BigDecimal capUsd;
    private final BigDecimal spentUsd;
    private final Long resetSeconds;

    /**
     * @param modality     the lower-case modality name whose sub-cap was hit
     *                     ({@code text}/{@code image}/{@code voice}/{@code video}).
     * @param capUsd       the effective cap for this window in USD (base cap +
     *                     any paid top-up credit already applied).
     * @param spentUsd     spend accumulated in this window so far, in USD.
     * @param resetSeconds seconds until the window resets, or {@code null} when
     *                     the metering store couldn't report a TTL.
     */
    public PerUserBudgetExhaustedException(
        String message, String modality, BigDecimal capUsd, BigDecimal spentUsd, Long resetSeconds
    ) {
        super(message);
        this.modality = modality;
        this.capUsd = capUsd;
        this.spentUsd = spentUsd;
        this.resetSeconds = resetSeconds;
    }

    public String getModality() {
        return modality;
    }

    public BigDecimal getCapUsd() {
        return capUsd;
    }

    public BigDecimal getSpentUsd() {
        return spentUsd;
    }

    /** Seconds until the window resets, or {@code null} if unknown. */
    public Long getResetSeconds() {
        return resetSeconds;
    }
}
