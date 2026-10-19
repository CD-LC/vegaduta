package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The {@code llm.pricing.missing} counter and bounded tag set that make
 * fallback-priced models (today: the gpt-5.x / gemini-3.x tier defaults, which
 * have no verified rate in this repo) visible without inventing prices.
 */
class LlmPricingCatalogFallbackMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong nanos = new AtomicLong(0);
    private final LlmPricingCatalog catalog = new LlmPricingCatalog(registry, nanos::get);

    private double missing(String provider, String model) {
        Counter c = registry.find(LlmPricingCatalog.PRICING_MISSING_METRIC)
            .tag("provider", provider).tag("model", model).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    void tierDefaultsWithoutAVerifiedRateStillPriceAtTheFallbackAndAreCounted() {
        BigDecimal cost = catalog.estimateCostUsd("OPENAI", "gpt-5.1", 1_000_000L, 1_000_000L, null, null);

        assertEquals(0, cost.compareTo(new BigDecimal("2.50")), "fallback $0.50/$2.00 per MTok, got " + cost);
        assertEquals(1.0, missing("openai", "gpt-5.1"));
    }

    @Test
    void geminiThreeTierDefaultsAreCountedUnderTheirOwnModelTag() {
        catalog.estimateCostUsd("GEMINI", "gemini-3.1-flash-lite", 10L, 10L, null, null);
        catalog.estimateCostUsd("GEMINI", "gemini-3.5-flash", 10L, 10L, null, null);
        catalog.estimateCostUsd("GEMINI", "gemini-3.5-flash", 10L, 10L, null, null);

        assertEquals(1.0, missing("gemini", "gemini-3.1-flash-lite"));
        assertEquals(2.0, missing("gemini", "gemini-3.5-flash"));
    }

    @Test
    void providerLessCallSitesTagAsUnknown() {
        catalog.estimateCostUsd("some-new-model", 10L, 10L);

        assertEquals(1.0, missing(LlmPricingCatalog.UNKNOWN_PROVIDER_TAG, "some-new-model"));
    }

    @Test
    void pricedModelsNeverTouchTheCounter() {
        catalog.estimateCostUsd("ANTHROPIC", "claude-sonnet-4-5", 10L, 10L, null, null);

        assertNull(registry.find(LlmPricingCatalog.PRICING_MISSING_METRIC).counter());
    }

    @Test
    void nothingToPriceIsNotAFallback() {
        assertNull(catalog.estimateCostUsd("OPENAI", "gpt-5.1", null, 10L, null, null));
        assertNull(registry.find(LlmPricingCatalog.PRICING_MISSING_METRIC).counter());
    }

    @Test
    void modelTagSetIsBounded() {
        for (int i = 0; i < LlmPricingCatalog.MAX_TRACKED_UNPRICED_MODELS + 25; i++) {
            catalog.estimateCostUsd("OPENAI", "unpriced-model-" + i, 1L, 1L, null, null);
        }

        long distinctModelTags = registry.find(LlmPricingCatalog.PRICING_MISSING_METRIC).counters().stream()
            .map(c -> c.getId().getTag("model"))
            .distinct()
            .count();
        assertTrue(distinctModelTags <= LlmPricingCatalog.MAX_TRACKED_UNPRICED_MODELS + 1,
            "at most the cap plus the overflow tag, got " + distinctModelTags);
        assertTrue(missing("openai", LlmPricingCatalog.OVERFLOW_MODEL_TAG) >= 25.0);
        // An already-tracked model keeps its own tag after the cap is reached.
        catalog.estimateCostUsd("OPENAI", "unpriced-model-0", 1L, 1L, null, null);
        assertEquals(2.0, missing("openai", "unpriced-model-0"));
    }

    @Test
    void overlongModelIdsAreTruncatedInTheTag() {
        String longId = "x".repeat(300);
        catalog.estimateCostUsd("OPENAI", longId, 1L, 1L, null, null);

        Counter c = registry.find(LlmPricingCatalog.PRICING_MISSING_METRIC).counter();
        assertNotNull(c);
        assertEquals(100, c.getId().getTag("model").length());
    }

    @Test
    void unmeteredCatalogStillPricesTheFallback() {
        BigDecimal cost = new LlmPricingCatalog().estimateCostUsd("gpt-5.1", 1_000_000L, 1_000_000L);
        assertEquals(0, cost.compareTo(new BigDecimal("2.50")));
    }
}
