package com.agenticai.core.agents.spendcap;

import com.agenticai.core.security.TenantLimitExceededException;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The tenant's monthly hosted-pool spend cap is reached. HTTP 402 with
 * {@code {"error":"spend_cap_reached", message, capUsd, monthToDateUsd, resetsAt}}
 * ({@code GlobalExceptionHandler}). A subclass of {@link TenantLimitExceededException}
 * on purpose: every existing quota path (Temporal do-not-retry, tool callbacks,
 * agent jobs) already treats that family as a deterministic, non-retryable stop.
 */
public class SpendCapReachedException extends TenantLimitExceededException {

    public static final String ERROR_CODE = "spend_cap_reached";

    private final BigDecimal capUsd;
    private final BigDecimal monthToDateUsd;
    private final Instant resetsAt;

    public SpendCapReachedException(BigDecimal capUsd, BigDecimal monthToDateUsd, Instant resetsAt) {
        super("This workspace has reached its monthly AI spend limit of $" + capUsd.toPlainString()
            + " on the platform-provided models. It resets on " + resetsAt.toString().substring(0, 10)
            + " (UTC). Your own provider keys (BYOK) keep working, or ask the platform team to raise the limit.");
        this.capUsd = capUsd;
        this.monthToDateUsd = monthToDateUsd;
        this.resetsAt = resetsAt;
    }

    public BigDecimal getCapUsd() {
        return capUsd;
    }

    public BigDecimal getMonthToDateUsd() {
        return monthToDateUsd;
    }

    public Instant getResetsAt() {
        return resetsAt;
    }
}
