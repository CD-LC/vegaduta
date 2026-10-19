package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.providers.ProviderName;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * V496: the per-agent cost view re-prices stored tokens from the tenant's own price table, so the
 * reasoning-token count has to be on the row, not only folded into {@code estimated_cost_usd}.
 */
class LlmCallLogReasoningTokensTest {

    private final LlmCallLogRepository repository = mock(LlmCallLogRepository.class);
    private final LlmCallLogService service =
            new LlmCallLogService(repository, new LlmPricingCatalog());

    private LlmCallLog savedRow() {
        ArgumentCaptor<LlmCallLog> row = ArgumentCaptor.forClass(LlmCallLog.class);
        verify(repository).save(row.capture());
        return row.getValue();
    }

    @Test
    void reasoningTokenCountIsStoredNotJustPriced() {
        service.record(
                null,
                "s1",
                ProviderName.GEMINI,
                "gemini-2.5-flash",
                1000L,
                200L,
                1200L,
                true,
                null,
                "r1",
                null,
                null,
                42L,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                500L);

        assertEquals(500L, savedRow().getReasoningTokens());
    }

    @Test
    void reasoningTokenCountStaysNullWhenTheProviderDidNotReportOne() {
        service.record(
                null,
                "s1",
                ProviderName.GEMINI,
                "gemini-2.5-flash",
                1000L,
                200L,
                1200L,
                true,
                null,
                "r1",
                null,
                null,
                42L,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        assertNull(savedRow().getReasoningTokens());
    }
}
