package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.providers.ProviderName;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.time.Instant;

/** The unit-level contract of the counter feed; the database behaviour is in ByokSpendCounterIT. */
class LlmCallLogServiceCounterTest {

    private final LlmCallLogRepository repository = mock(LlmCallLogRepository.class);
    private final LlmSpendCounterWriter writer = mock(LlmSpendCounterWriter.class);
    private final LlmCallLogService service =
            new LlmCallLogService(
                    repository, new LlmPricingCatalog(new SimpleMeterRegistry(), System::nanoTime));

    private void record(Long promptTokens, Long completionTokens, boolean success) {
        service.record(
                null,
                "s1",
                ProviderName.OPENAI,
                "gpt-4o-mini",
                promptTokens,
                completionTokens,
                null,
                success,
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
    }

    @Test
    void pricedCallAddsToTheCounter() {
        service.setCounterWriter(writer);
        record(1000L, 500L, true);
        verify(writer).add(any(), any());
    }

    @Test
    void callWithNoUsageSkipsTheCounter() {
        service.setCounterWriter(writer);
        record(null, null, false);
        verify(writer, never()).add(any(), any());
    }

    @Test
    void counterFailureDoesNotPropagate() {
        service.setCounterWriter(writer);
        doThrow(new IllegalStateException("boom")).when(writer).add(any(), any());
        assertDoesNotThrow(() -> record(1000L, 500L, true));
    }

    @Test
    void withoutAWriterTheCounterIsSimplySkipped() {
        assertDoesNotThrow(() -> record(1000L, 500L, true));
    }
}
