package com.agenticai.core.agents.collab;

import java.math.BigDecimal;

/**
 * Pure rules for the per-agent run cap (unit T4). No Spring, no I/O.
 *
 * <p>An agent's own cap can only LOWER the platform cap
 * ({@code app.guardrails.run-budget.usd}). When the platform cap is off (0) the agent's value
 * is used on its own, up to {@code app.guardrails.run-budget.max-per-agent-usd}.
 * A null or non-positive agent value means "use the platform cap".
 */
public final class RunBudgetCaps {

    /** Matches the column: NUMERIC(10,4). */
    public static final int MAX_SCALE = 4;
    private static final BigDecimal COLUMN_MAX = new BigDecimal("999999.9999");

    private RunBudgetCaps() {}

    /** The cap a turn of this agent actually runs under. Never null; 0 = no cap. */
    public static BigDecimal effectiveCap(
        BigDecimal globalCapUsd, BigDecimal agentCapUsd, boolean overrideEnabled, BigDecimal maxPerAgentUsd
    ) {
        BigDecimal global = positiveOrZero(globalCapUsd);
        if (!overrideEnabled || agentCapUsd == null || agentCapUsd.signum() <= 0) {
            return global;
        }
        BigDecimal ceiling = ceiling(globalCapUsd, maxPerAgentUsd);
        return ceiling != null && agentCapUsd.compareTo(ceiling) > 0 ? ceiling : agentCapUsd;
    }

    /**
     * The highest value an agent may store: the platform cap when it is on, else the
     * per-agent maximum; null when neither is set (no upper bound beyond the column).
     */
    public static BigDecimal ceiling(BigDecimal globalCapUsd, BigDecimal maxPerAgentUsd) {
        BigDecimal global = positiveOrZero(globalCapUsd);
        if (global.signum() > 0) {
            return global;
        }
        BigDecimal max = positiveOrZero(maxPerAgentUsd);
        return max.signum() > 0 ? max : null;
    }

    /**
     * Validates a value a user asked to store. Null is allowed (clears the agent cap).
     *
     * @return the value to store, at the column scale, or null
     * @throws IllegalArgumentException when not positive, too precise, or above the ceiling
     */
    public static BigDecimal validateForStorage(
        BigDecimal requested, BigDecimal globalCapUsd, BigDecimal maxPerAgentUsd
    ) {
        if (requested == null) {
            return null;
        }
        if (requested.signum() <= 0) {
            throw new IllegalArgumentException("runBudgetUsd must be greater than 0, or null to clear it");
        }
        BigDecimal stripped = requested.stripTrailingZeros();
        if (stripped.scale() > MAX_SCALE) {
            throw new IllegalArgumentException("runBudgetUsd supports at most " + MAX_SCALE + " decimal places");
        }
        if (requested.compareTo(COLUMN_MAX) > 0) {
            throw new IllegalArgumentException("runBudgetUsd is too large");
        }
        BigDecimal ceiling = ceiling(globalCapUsd, maxPerAgentUsd);
        if (ceiling != null && requested.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException(
                "runBudgetUsd cannot be higher than the platform cap of $" + ceiling.toPlainString() + " per run");
        }
        return requested.setScale(MAX_SCALE, java.math.RoundingMode.UNNECESSARY);
    }

    private static BigDecimal positiveOrZero(BigDecimal value) {
        return value == null || value.signum() <= 0 ? BigDecimal.ZERO : value;
    }
}
