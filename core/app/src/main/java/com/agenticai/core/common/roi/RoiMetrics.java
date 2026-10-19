package com.agenticai.core.common.roi;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The ratio and ROI definitions, with their zero-denominator rules, as pure functions.
 *
 * <p>Definitions (the page and the CSV use these words):
 *
 * <ul>
 *   <li><b>handover rate</b> = conversations handed to a human / conversations with a recorded
 *       outcome
 *   <li><b>human-correction rate</b> = conversations a human corrected / conversations with a
 *       recorded outcome
 *   <li><b>approval-denial rate</b> = approvals rejected / approvals decided (status other than
 *       PENDING)
 *   <li><b>cost per resolved conversation</b> = estimated LLM cost / resolved conversations
 * </ul>
 *
 * Every ratio is {@code null}, not 0, when its denominator is 0: "no data" is not "perfect".
 */
public final class RoiMetrics {

    /** Marker on every ROI figure; the page prints it next to each one. */
    public static final String ROI_BASIS = "based on your assumptions";

    private RoiMetrics() {}

    public static BigDecimal ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    public static BigDecimal perUnit(BigDecimal total, long units) {
        if (total == null || units <= 0) {
            return null;
        }
        return total.divide(BigDecimal.valueOf(units), 6, RoundingMode.HALF_UP);
    }

    /** What the tenant typed for one agent. Both fields optional; neither is ever defaulted. */
    public record Assumption(
            BigDecimal valuePerResolution,
            String valueCurrency,
            BigDecimal minutesSavedPerResolution) {}

    /**
     * What the tenant actually pays: BYOK provider cost (USD) and platform charges (minor units).
     */
    public record CostBasis(
            BigDecimal byokCostUsd,
            long platformChargesMinor,
            String platformChargesCurrency,
            boolean complete) {}

    public record Roi(
            BigDecimal valueTotal,
            BigDecimal minutesSavedTotal,
            BigDecimal netValue,
            BigDecimal roiPercent,
            String note) {}

    /**
     * ROI from tenant-entered assumptions only. {@code valueTotal} and {@code minutesSavedTotal}
     * are assumption x resolved. {@code netValue}/{@code roiPercent} need a value in the same
     * currency as the cost (USD, optionally plus USD platform charges); otherwise they are null
     * with {@code CURRENCY_MISMATCH} - the platform does no currency conversion because it has no
     * recorded exchange rate to convert with. A cost of zero gives no percentage.
     */
    public static Roi roi(long resolved, Assumption a, CostBasis cost) {
        if (a == null
                || (a.valuePerResolution() == null && a.minutesSavedPerResolution() == null)) {
            return new Roi(null, null, null, null, "NO_ASSUMPTIONS");
        }
        BigDecimal n = BigDecimal.valueOf(resolved);
        BigDecimal valueTotal =
                a.valuePerResolution() == null ? null : a.valuePerResolution().multiply(n);
        BigDecimal minutes =
                a.minutesSavedPerResolution() == null
                        ? null
                        : a.minutesSavedPerResolution().multiply(n);
        if (valueTotal == null) {
            return new Roi(null, minutes, null, null, "NO_VALUE_ASSUMPTION");
        }
        boolean usdValue = "USD".equalsIgnoreCase(a.valueCurrency());
        boolean usdCharges =
                cost.platformChargesMinor() == 0
                        || "USD".equalsIgnoreCase(cost.platformChargesCurrency());
        if (!usdValue || !usdCharges) {
            return new Roi(valueTotal, minutes, null, null, "CURRENCY_MISMATCH");
        }
        BigDecimal total =
                (cost.byokCostUsd() == null ? BigDecimal.ZERO : cost.byokCostUsd())
                        .add(BigDecimal.valueOf(cost.platformChargesMinor()).movePointLeft(2));
        BigDecimal net = valueTotal.subtract(total);
        BigDecimal pct =
                total.signum() == 0
                        ? null
                        : net.multiply(BigDecimal.valueOf(100))
                                .divide(total, 1, RoundingMode.HALF_UP);
        String note =
                !cost.complete() ? "COST_INCOMPLETE" : (pct == null ? "NO_COST_RECORDED" : "OK");
        return new Roi(valueTotal, minutes, net, pct, note);
    }
}
