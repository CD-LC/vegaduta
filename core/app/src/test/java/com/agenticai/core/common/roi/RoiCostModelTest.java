package com.agenticai.core.common.roi;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.roi.RoiCostModel.Cost;
import com.agenticai.core.common.roi.RoiCostModel.Rate;
import com.agenticai.core.common.roi.RoiCostModel.Usage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RoiCostModelTest {

    private static Rate rate(
            String provider,
            String prefix,
            String in,
            String out,
            String cw,
            String cr,
            String from) {
        return new Rate(
                UUID.randomUUID(),
                provider,
                prefix,
                bd(in),
                bd(out),
                bd(cw),
                bd(cr),
                LocalDate.parse(from));
    }

    private static BigDecimal bd(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    @Test
    void pricesInputAndOutputPerMillionTokens() {
        Cost c =
                RoiCostModel.price(
                        rate("ANTHROPIC", "claude", "3", "15", null, null, "2026-01-01"),
                        new Usage(2_000_000, 1_000_000, 0, 0, 0));
        assertThat(c.priced()).isTrue();
        assertThat(c.usd()).isEqualByComparingTo("21"); // 2 x 3 + 1 x 15
    }

    @Test
    void reasoningBilledAtOutputRateAndCacheAtTheirOwnRates() {
        Cost c =
                RoiCostModel.price(
                        rate("ANTHROPIC", "claude", "3", "15", "3.75", "0.30", "2026-01-01"),
                        new Usage(1_000_000, 0, 1_000_000, 1_000_000, 1_000_000));
        assertThat(c.usd()).isEqualByComparingTo("22.05"); // 3 + 15 (reasoning) + 3.75 + 0.30
    }

    @Test
    void noPriceIsUnpricedNotZero() {
        Cost c = RoiCostModel.price(null, new Usage(10, 10, 0, 0, 0));
        assertThat(c.priced()).isFalse();
        assertThat(c.usd()).isNull();
        assertThat(c.unpricedReason()).isEqualTo("NO_PRICE");
    }

    @Test
    void blankRateIsUnpriced() {
        assertThat(
                        RoiCostModel.price(
                                        rate("OPENAI", "gpt", "1", null, null, null, "2026-01-01"),
                                        new Usage(10, 10, 0, 0, 0))
                                .priced())
                .isFalse();
        assertThat(
                        RoiCostModel.price(
                                        rate("OPENAI", "gpt", null, "1", null, null, "2026-01-01"),
                                        new Usage(10, 10, 0, 0, 0))
                                .priced())
                .isFalse();
    }

    @Test
    void cacheTokensWithNoCacheRateAreUnpricedRatherThanAssumedAtInputRate() {
        Rate r = rate("ANTHROPIC", "claude", "3", "15", null, null, "2026-01-01");
        assertThat(RoiCostModel.price(r, new Usage(10, 10, 5, 0, 0)).unpricedReason())
                .isEqualTo("CACHE_WRITE_RATE_BLANK");
        assertThat(RoiCostModel.price(r, new Usage(10, 10, 0, 5, 0)).unpricedReason())
                .isEqualTo("CACHE_READ_RATE_BLANK");
        assertThat(RoiCostModel.price(r, new Usage(10, 10, 0, 0, 0)).priced())
                .isTrue(); // no cache tokens -> no cache rate needed
    }

    @Test
    void zeroUsageNeedsNoPriceAndCostsZero() {
        Cost c = RoiCostModel.price(null, new Usage(0, 0, 0, 0, 0));
        assertThat(c.priced()).isTrue();
        assertThat(c.usd()).isEqualByComparingTo("0");
    }

    @Test
    void selectsLongestPrefixThenLatestEffectiveDateNotAfterTheDay() {
        Rate generic = rate("ANTHROPIC", "claude", "1", "1", null, null, "2026-01-01");
        Rate sonnetOld = rate("ANTHROPIC", "claude-sonnet", "3", "15", null, null, "2026-01-01");
        Rate sonnetNew = rate("ANTHROPIC", "claude-sonnet", "4", "20", null, null, "2026-06-01");
        Rate otherProvider = rate("OPENAI", "claude-sonnet", "9", "9", null, null, "2026-01-01");
        List<Rate> all = List.of(generic, sonnetOld, sonnetNew, otherProvider);

        assertThat(
                        RoiCostModel.select(
                                all,
                                "ANTHROPIC",
                                "Claude-Sonnet-5-20260101",
                                LocalDate.parse("2026-05-31")))
                .contains(sonnetOld);
        assertThat(
                        RoiCostModel.select(
                                all, "ANTHROPIC", "claude-sonnet-5", LocalDate.parse("2026-06-01")))
                .contains(sonnetNew);
        assertThat(
                        RoiCostModel.select(
                                all, "ANTHROPIC", "claude-haiku-5", LocalDate.parse("2026-06-01")))
                .contains(generic);
    }

    @Test
    void noPriceBeforeItsEffectiveDateAndNoneForUnknownModel() {
        List<Rate> all = List.of(rate("ANTHROPIC", "claude", "3", "15", null, null, "2026-06-01"));
        assertThat(RoiCostModel.select(all, "ANTHROPIC", "claude-x", LocalDate.parse("2026-05-31")))
                .isEmpty();
        assertThat(RoiCostModel.select(all, "OPENAI", "gpt-5", LocalDate.parse("2026-07-01")))
                .isEmpty();
        assertThat(RoiCostModel.select(all, "ANTHROPIC", null, LocalDate.parse("2026-07-01")))
                .isEmpty();
    }
}
