package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderHealth;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /api/providers} health: read from the workspace's own call log, so a key the provider refuses is
 * flagged before someone picks it (prod 2026-10-05: Vertex stayed selectable with no warning).
 */
class CallLogProviderHealthTest {

    private static final Duration COOLDOWN = Duration.ofHours(6);

    private LlmCallLogRepository callLog;
    private CallLogProviderUsageSignal signal;

    @BeforeEach
    void setUp() {
        callLog = mock(LlmCallLogRepository.class);
        when(callLog.findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(any(), eq(true), any()))
            .thenReturn(Optional.empty());
        when(callLog.findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(any(), eq(false), any()))
            .thenReturn(Optional.empty());
        signal = new CallLogProviderUsageSignal(callLog);
        // a fresh tenant per test keeps the short TTL cache from leaking between tests
        TenantContext.set(TenantContext.of("t-" + System.nanoTime(), "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static LlmCallLog row(Instant at, String error, String stack) {
        LlmCallLog row = mock(LlmCallLog.class);
        when(row.getStartedAt()).thenReturn(at);
        when(row.getErrorMessage()).thenReturn(error);
        when(row.getStackTrace()).thenReturn(stack);
        when(row.getModel()).thenReturn("m");
        return row;
    }

    private void log(ProviderName provider, boolean success, LlmCallLog row, long count) {
        when(callLog.findTopByProviderNameAndSuccessAndStartedAtAfterOrderByStartedAtDesc(eq(provider), eq(success), any()))
            .thenReturn(Optional.of(row));
        when(callLog.countByProviderNameAndSuccessAndStartedAtAfter(eq(provider), eq(success), any())).thenReturn(count);
    }

    @Test
    void aKeyTheProviderRefusesIsRejectedWithCountsTheNewestErrorAndThePauseEnd() {
        Instant now = Instant.now();
        LlmCallLog failure = row(now.minusSeconds(60),
            "Failed to generate content", "x\nCaused by: com.google.genai.errors.ClientException: 403 PERMISSION_DENIED");
        log(ProviderName.GOOGLE_VERTEX, false, failure, 362);

        ProviderHealth health = signal.health(List.of(ProviderName.GOOGLE_VERTEX), COOLDOWN).get(ProviderName.GOOGLE_VERTEX);

        assertEquals(ProviderHealth.REJECTED, health.status());
        assertEquals(362, health.failures());
        assertEquals(0, health.successes());
        assertEquals(14, health.windowDays());
        assertEquals("Failed to generate content", health.lastError());
        assertEquals(health.lastFailureAt().plus(COOLDOWN), health.backgroundPausedUntil());
    }

    @Test
    void aKeyThatAnsweredAfterItsLastFailureIsOk() {
        Instant now = Instant.now();
        LlmCallLog failure = row(now.minusSeconds(900), "HTTP 401", null);
        LlmCallLog success = row(now.minusSeconds(60), null, null);
        log(ProviderName.OPENROUTER, false, failure, 3);
        log(ProviderName.OPENROUTER, true, success, 40);

        ProviderHealth health = signal.health(List.of(ProviderName.OPENROUTER), COOLDOWN).get(ProviderName.OPENROUTER);

        assertEquals(ProviderHealth.OK, health.status());
        assertEquals(3, health.failures());
        assertEquals(40, health.successes());
        assertNull(health.backgroundPausedUntil());
    }

    @Test
    void aTransientFailureIsNotARejection() {
        LlmCallLog failure = row(Instant.now().minusSeconds(10), "HTTP 429: rate limit exceeded", null);
        log(ProviderName.GROQ, false, failure, 1);

        ProviderHealth health = signal.health(List.of(ProviderName.GROQ), COOLDOWN).get(ProviderName.GROQ);

        assertEquals(ProviderHealth.OK, health.status());
        assertEquals("HTTP 429: rate limit exceeded", health.lastError());
    }

    @Test
    void aProviderWithNoCallInTheWindowIsUnknown() {
        ProviderHealth health = signal.health(List.of(ProviderName.MISTRAL), COOLDOWN).get(ProviderName.MISTRAL);

        assertEquals(ProviderHealth.UNKNOWN, health.status());
        assertEquals(0, health.failures());
    }

    @Test
    void theErrorSummaryNeverEchoesAKeyShapedString() {
        String summary = CallLogProviderUsageSignal.summarizeError(
            "HTTP 401: Incorrect API key provided: sk-proj-abcdefghijklmnopqrstuvwxyz0123456789\nsecond line", null);

        assertFalse(summary.contains("abcdefghijklmnop"), summary);
        assertTrue(summary.contains("Incorrect API key provided"), summary);
        assertFalse(summary.contains("second line"));
        assertNull(CallLogProviderUsageSignal.summarizeError(null, null));
        assertEquals(163, CallLogProviderUsageSignal.summarizeError("word ".repeat(60), null).length());
    }

    @Test
    void noProvidersMeansNoHealth() {
        assertEquals(Map.of(), signal.health(List.of(), COOLDOWN));
    }
}
