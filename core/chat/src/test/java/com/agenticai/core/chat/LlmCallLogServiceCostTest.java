package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 2026-10-05 cost-tracing audit regression: the durable {@code llm_call_log}
 * row must price the same token buckets the {@code llm.cost.usd} counter and
 * the OTel/Langfuse span metadata do. Before the fix {@link
 * LlmCallLogService#record} re-priced through the overload with no reasoning
 * argument, so a Gemini thinking call was stored CHEAPER than the meter the
 * same advisor emitted - and every rollup/dashboard reading the row
 * under-reported. These pin the row's {@code estimated_cost_usd} against
 * {@link LlmPricingCatalog}'s full-shape estimate directly.
 */
class LlmCallLogServiceCostTest {

    private final LlmCallLogRepository repository = mock(LlmCallLogRepository.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LlmPricingCatalog pricingCatalog = new LlmPricingCatalog(meterRegistry, System::nanoTime);
    private final LlmCallLogService service = new LlmCallLogService(repository, pricingCatalog);

    private LlmCallLog savedRow() {
        ArgumentCaptor<LlmCallLog> row = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(repository).save(row.capture());
        return row.getValue();
    }

    @Test
    void persistedCostIncludesBillableReasoningTokens() {
        // gemini-2.5-flash: $0.30/MTok input, $2.50/MTok output.
        // 1000 prompt -> $0.000300; 200 completion -> $0.000500;
        // 500 thinking tokens (reported outside completion) -> $0.001250.
        service.record(null, "s1", ProviderName.GEMINI, "gemini-2.5-flash",
            1000L, 200L, 1200L, true, null, "r1", null, null, 42L, Instant.now(),
            null, null, null, null, null, null, 500L);

        LlmCallLog row = savedRow();
        assertNotNull(row.getEstimatedCostUsd());
        assertEquals(0, new BigDecimal("0.002050").compareTo(row.getEstimatedCostUsd()));
    }

    @Test
    void theCompatOverloadStillPricesWithoutReasoning() {
        // The pre-2026-10-05 signature delegates with null reasoning - same
        // arithmetic as before for callers that don't have a reasoning count.
        service.record(null, "s1", ProviderName.GEMINI, "gemini-2.5-flash",
            1000L, 200L, 1200L, true, null, "r1", null, null, 42L, Instant.now(),
            null, null, null, null, null);

        LlmCallLog row = savedRow();
        assertEquals(0, new BigDecimal("0.000800").compareTo(row.getEstimatedCostUsd()));
    }

    @Test
    void anAdvisorEstimateOfZeroIsStoredNotRePricedAtTheDefaultRate() {
        // A hosted-pool row run locally (zero_cost) is priced at $0 by the advisor's own catalog. The service holds
        // the shared catalog, which knows nothing of "qwen3.5:4b" and would book the unknown-model default rate.
        service.record(null, "s1", ProviderName.PLATFORM_HOSTED, "qwen3.5:4b-ctx32k",
            1000L, 200L, 1200L, true, null, "r1", null, null, 42L, Instant.now(),
            null, null, null, null, null, null, null, BigDecimal.ZERO);

        assertEquals(0, BigDecimal.ZERO.compareTo(savedRow().getEstimatedCostUsd()));
        assertEquals(0.0,
            meterRegistry.counter(LlmPricingCatalog.PRICING_MISSING_METRIC,
                "provider", "platform_hosted", "model", "qwen3.5:4b-ctx32k").count());
    }

    @Test
    void fallbackPricedRowTagsTheRealProviderNotUnknown() {
        // Before the fix the durable path called the provider-less overload,
        // so llm.pricing.missing attributed every unpriced durable row to
        // provider=unknown even when the caller knew the provider.
        service.record(null, "s1", ProviderName.ANTHROPIC, "claude-nonexistent-9",
            100L, 50L, 150L, true, null, "r1", null, null, 10L, Instant.now(),
            null, null, null, null, null, null, null);

        assertEquals(1.0,
            meterRegistry.counter(LlmPricingCatalog.PRICING_MISSING_METRIC,
                "provider", "anthropic", "model", "claude-nonexistent-9").count());
        assertEquals(0.0,
            meterRegistry.counter(LlmPricingCatalog.PRICING_MISSING_METRIC,
                "provider", "unknown", "model", "claude-nonexistent-9").count());
    }
}
