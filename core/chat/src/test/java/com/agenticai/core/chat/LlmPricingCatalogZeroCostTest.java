package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** V484 - the $0 view used for hosted-pool rows the operator runs themselves. */
class LlmPricingCatalogZeroCostTest {

    @Test
    void everyOverloadPricesAtZeroButKeepsTheUnknownUsageContract() {
        LlmPricingCatalog zero = LlmPricingCatalog.zeroCost();

        assertEquals(0, BigDecimal.ZERO.compareTo(zero.estimateCostUsd("qwen3.5:4b", 1_000L, 500L)));
        assertEquals(0, BigDecimal.ZERO.compareTo(zero.estimateCostUsd("gpt-4o", 1_000L, 500L, 10L, 20L)));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                zero.estimateCostUsd("ollama", "gemini-2.0-flash", 1_000L, 500L, null, null, 300L)));
        assertNull(zero.estimateCostUsd("qwen3.5:4b", null, 500L), "no usable counts stays null");
        assertNull(zero.estimateCostUsd(null, 1L, 1L));
    }

    @Test
    void ratesAreZeroAndTheViewIsAStatelessSingleton() {
        LlmPricingCatalog zero = LlmPricingCatalog.zeroCost();
        LlmPricingCatalog.RatesPerMillionTokens rates = zero.lookupRatesPerMillionTokens("anything");
        assertEquals(0, BigDecimal.ZERO.compareTo(rates.inputPerMillionUsd()));
        assertEquals(0, BigDecimal.ZERO.compareTo(rates.outputPerMillionUsd()));
        assertNull(zero.lookupRatesPerMillionTokens(" "));
        assertSame(zero, LlmPricingCatalog.zeroCost());
    }

    @Test
    void theRealCatalogStillChargesTheSameLocalTagAtTheFallbackRate() {
        BigDecimal real = new LlmPricingCatalog().estimateCostUsd("qwen3.5:4b", 1_000L, 500L);
        assertTrue(real.signum() > 0, "the fallback rate is what zero_cost exists to avoid: " + real);
    }
}
