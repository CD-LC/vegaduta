package com.agenticai.core.agents.collab;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Per-run (per chat turn) USD budget for delegation tools. One instance is
 * shared by every delegate tool resolved for one turn, so the cap covers
 * {@code ask_agent_*} and {@code delegate_parallel} together.
 *
 * <p>Scope: delegation tools ONLY. Other tool iterations in the turn are bounded
 * by the existing {@code app.guardrails.max-tool-calls-per-turn} guard, which this
 * class does not replace.
 *
 * <p>Delegated calls return only text (no provider usage), so spend is ESTIMATED:
 * characters (~4 chars/token) priced at configurable blended per-million-token
 * rates, floored per delegate call at the average-branch estimate because a
 * delegate runs a whole agent loop (retrieval, tools, several model turns), not
 * one completion. It is a runaway guard, not billing; the authoritative ledger
 * (LlmCallLogService / CacheTokenAccounting) is unchanged and does not feed this.
 *
 * <p>Configuration is Spring-bound under {@code app.guardrails.run-budget.*}
 * (see application.yml) and OFF by default ({@code usd} = 0).
 */
public final class RunBudget {

    public static final String STOP_REASON = "run_budget_exceeded";
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final BigDecimal capUsd;
    private final BigDecimal inputUsdPerM;
    private final BigDecimal outputUsdPerM;
    private final long avgBranchTokens;
    private BigDecimal spentUsd = BigDecimal.ZERO;

    public RunBudget(BigDecimal capUsd, BigDecimal inputUsdPerM, BigDecimal outputUsdPerM, long avgBranchTokens) {
        this.capUsd = capUsd == null ? BigDecimal.ZERO : capUsd;
        this.inputUsdPerM = inputUsdPerM == null ? BigDecimal.ZERO : inputUsdPerM;
        this.outputUsdPerM = outputUsdPerM == null ? BigDecimal.ZERO : outputUsdPerM;
        this.avgBranchTokens = Math.max(1, avgBranchTokens);
    }

    public boolean isEnabled() {
        return capUsd.signum() > 0;
    }

    public synchronized BigDecimal spentUsd() {
        return spentUsd;
    }

    public BigDecimal capUsd() {
        return capUsd;
    }

    /** USD for a call with the given token counts at the configured blended rates. */
    BigDecimal price(long inputTokens, long outputTokens) {
        return inputUsdPerM.multiply(BigDecimal.valueOf(inputTokens)).divide(MILLION, 8, RoundingMode.HALF_UP)
            .add(outputUsdPerM.multiply(BigDecimal.valueOf(outputTokens)).divide(MILLION, 8, RoundingMode.HALF_UP));
    }

    /** Conservative pre-run estimate: branches x average tokens (80% input / 20% output). */
    public BigDecimal estimateUsd(int branches) {
        long in = avgBranchTokens * 4 / 5;
        long out = avgBranchTokens - in;
        return price(in, out).multiply(BigDecimal.valueOf(Math.max(0, branches)));
    }

    /**
     * Records one finished DELEGATE call: the text-based estimate, but never less than
     * one average branch, since the delegate ran a full agent loop behind its short text.
     */
    public synchronized void recordDelegate(String input, String output) {
        BigDecimal floor = spentUsd.add(estimateUsd(1));
        record(input, output);
        if (spentUsd.compareTo(floor) < 0) {
            spentUsd = floor;
        }
    }

    /** Records one finished call, estimating tokens from the text lengths. */
    public synchronized void record(String input, String output) {
        long in = input == null ? 0 : (input.length() + 3) / 4;
        long out = output == null ? 0 : (output.length() + 3) / 4;
        spentUsd = spentUsd.add(price(in, out));
    }

    public synchronized boolean isExceeded() {
        return isEnabled() && spentUsd.compareTo(capUsd) >= 0;
    }

    /** How many of {@code requested} branches fit in the remaining budget (at least 0). */
    public synchronized int affordableBranches(int requested) {
        if (!isEnabled()) {
            return requested;
        }
        BigDecimal per = estimateUsd(1);
        if (per.signum() <= 0) {
            return requested;
        }
        BigDecimal remaining = capUsd.subtract(spentUsd);
        if (remaining.signum() <= 0) {
            return 0;
        }
        int fit = remaining.divide(per, 0, RoundingMode.DOWN).intValue();
        return Math.min(requested, fit);
    }

    /** Human-readable stop reason shown to the model (and relayed to the user). */
    public synchronized String stopMessage() {
        return String.format(Locale.ROOT,
            "Run budget reached: about $%.2f (estimated) spent of the $%.2f per-run cap. Stopping further"
                + " delegation; summarise what you have and tell the user the run was stopped by the cost cap.",
            spentUsd, capUsd);
    }
}
