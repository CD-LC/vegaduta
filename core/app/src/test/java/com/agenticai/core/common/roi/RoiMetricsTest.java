package com.agenticai.core.common.roi;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.roi.RoiCostService.Grain;
import com.agenticai.core.common.roi.RoiMetrics.Assumption;
import com.agenticai.core.common.roi.RoiMetrics.CostBasis;
import com.agenticai.core.common.roi.RoiMetrics.Roi;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoiMetricsTest {

    private static final CostBasis TEN_USD = new CostBasis(new BigDecimal("10"), 0, null, true);

    @Test
    void ratiosAreNullNotZeroWhenTheDenominatorIsZero() {
        assertThat(RoiMetrics.ratio(0, 0)).isNull();
        assertThat(RoiMetrics.ratio(3, 0)).isNull();
        assertThat(RoiMetrics.ratio(1, 4)).isEqualByComparingTo("0.25");
        assertThat(RoiMetrics.ratio(0, 5)).isEqualByComparingTo("0");
    }

    @Test
    void costPerResolvedIsNullWhenNothingWasResolved() {
        assertThat(RoiMetrics.perUnit(new BigDecimal("12"), 0)).isNull();
        assertThat(RoiMetrics.perUnit(null, 3)).isNull();
        assertThat(RoiMetrics.perUnit(new BigDecimal("12"), 4)).isEqualByComparingTo("3");
    }

    @Test
    void noAssumptionsMeansNoRoiFigures() {
        Roi r = RoiMetrics.roi(5, null, TEN_USD);
        assertThat(r.valueTotal()).isNull();
        assertThat(r.netValue()).isNull();
        assertThat(r.roiPercent()).isNull();
        assertThat(r.note()).isEqualTo("NO_ASSUMPTIONS");
        assertThat(RoiMetrics.roi(5, new Assumption(null, null, null), TEN_USD).note())
                .isEqualTo("NO_ASSUMPTIONS");
    }

    @Test
    void roiComesOnlyFromTheTenantsAssumptions() {
        Roi r =
                RoiMetrics.roi(
                        5,
                        new Assumption(new BigDecimal("6"), "USD", new BigDecimal("12")),
                        TEN_USD);
        assertThat(r.valueTotal()).isEqualByComparingTo("30");
        assertThat(r.minutesSavedTotal()).isEqualByComparingTo("60");
        assertThat(r.netValue()).isEqualByComparingTo("20");
        assertThat(r.roiPercent()).isEqualByComparingTo("200.0");
        assertThat(r.note()).isEqualTo("OK");
    }

    @Test
    void zeroResolvedGivesZeroValueAndANegativeNetNotADivisionError() {
        Roi r = RoiMetrics.roi(0, new Assumption(new BigDecimal("6"), "USD", null), TEN_USD);
        assertThat(r.valueTotal()).isEqualByComparingTo("0");
        assertThat(r.netValue()).isEqualByComparingTo("-10");
        assertThat(r.roiPercent()).isEqualByComparingTo("-100.0");
    }

    @Test
    void zeroCostGivesNoPercentage() {
        Roi r =
                RoiMetrics.roi(
                        2,
                        new Assumption(new BigDecimal("6"), "USD", null),
                        new CostBasis(BigDecimal.ZERO, 0, null, true));
        assertThat(r.roiPercent()).isNull();
        assertThat(r.note()).isEqualTo("NO_COST_RECORDED");
    }

    @Test
    void minutesOnlyAssumptionStillCountsMinutesButNotMoney() {
        Roi r = RoiMetrics.roi(4, new Assumption(null, null, new BigDecimal("2.5")), TEN_USD);
        assertThat(r.minutesSavedTotal()).isEqualByComparingTo("10");
        assertThat(r.netValue()).isNull();
        assertThat(r.note()).isEqualTo("NO_VALUE_ASSUMPTION");
    }

    @Test
    void differentCurrencyIsNotConvertedBecauseNoRateIsRecorded() {
        Roi r = RoiMetrics.roi(5, new Assumption(new BigDecimal("500"), "INR", null), TEN_USD);
        assertThat(r.valueTotal()).isEqualByComparingTo("2500");
        assertThat(r.netValue()).isNull();
        assertThat(r.note()).isEqualTo("CURRENCY_MISMATCH");
        Roi inrCharges =
                RoiMetrics.roi(
                        5,
                        new Assumption(new BigDecimal("6"), "USD", null),
                        new CostBasis(BigDecimal.ZERO, 5000, "INR", true));
        assertThat(inrCharges.note()).isEqualTo("CURRENCY_MISMATCH");
    }

    @Test
    void usdPlatformChargesAreAddedToTheCostBasis() {
        Roi r =
                RoiMetrics.roi(
                        10,
                        new Assumption(new BigDecimal("2"), "USD", null),
                        new CostBasis(
                                new BigDecimal("5"),
                                500,
                                "USD",
                                true)); // $5 provider + $5.00 charges
        assertThat(r.netValue()).isEqualByComparingTo("10");
        assertThat(r.roiPercent()).isEqualByComparingTo("100.0");
    }

    @Test
    void incompleteCostIsFlaggedOnTheRoi() {
        Roi r =
                RoiMetrics.roi(
                        5,
                        new Assumption(new BigDecimal("6"), "USD", null),
                        new CostBasis(new BigDecimal("10"), 0, null, false));
        assertThat(r.note()).isEqualTo("COST_INCOMPLETE");
    }

    // ---- aggregation ---------------------------------------------------------------------------

    private static Grain grain(
            String provider, String model, long calls, RoiCostModel.Cost cost, long withoutUsage) {
        return new Grain(
                LocalDate.parse("2026-10-01"),
                UUID.randomUUID(),
                null,
                null,
                null,
                provider,
                model,
                calls,
                0,
                withoutUsage,
                new RoiCostModel.Usage(100, 50, 0, 0, 0),
                cost);
    }

    private static RoiCostModel.Cost priced(String usd) {
        return RoiCostModel.price(
                new RoiCostModel.Rate(
                        UUID.randomUUID(),
                        "X",
                        "m",
                        new BigDecimal(usd),
                        BigDecimal.ZERO,
                        null,
                        null,
                        LocalDate.parse("2026-01-01")),
                new RoiCostModel.Usage(1_000_000, 0, 0, 0, 0));
    }

    @Test
    void rollupSplitsByokFromPlatformHostedAndExcludesUnpricedCalls() {
        var row =
                RoiCostService.row(
                        "k",
                        "label",
                        List.of(
                                grain("ANTHROPIC", "claude", 2, priced("3"), 0),
                                grain("PLATFORM_HOSTED", "pool", 1, priced("1"), 0),
                                grain(
                                        "OPENAI",
                                        "gpt-unlisted",
                                        4,
                                        RoiCostModel.price(
                                                null, new RoiCostModel.Usage(1, 1, 0, 0, 0)),
                                        0)));
        assertThat(row.byokEstimatedCostUsd()).isEqualByComparingTo("3");
        assertThat(row.platformHostedEstimatedCostUsd()).isEqualByComparingTo("1");
        assertThat(row.estimatedCostUsd()).isEqualByComparingTo("4");
        assertThat(row.pricedCalls()).isEqualTo(3);
        assertThat(row.unpricedCalls()).isEqualTo(4);
        assertThat(row.costComplete()).isFalse();
    }

    @Test
    void emptyRollupIsZeroAndCompleteNotNull() {
        var row = RoiCostService.row("k", "label", List.of());
        assertThat(row.estimatedCostUsd()).isEqualByComparingTo("0");
        assertThat(row.costComplete()).isTrue();
    }

    @Test
    void successfulCallsWithoutUsageMakeTheCostIncomplete() {
        var row =
                RoiCostService.row(
                        "k", "l", List.of(grain("ANTHROPIC", "claude", 3, priced("3"), 2)));
        assertThat(row.callsWithoutUsage()).isEqualTo(2);
        assertThat(row.costComplete()).isFalse();
    }
}
