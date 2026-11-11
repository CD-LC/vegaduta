package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.ProviderName;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * One persisted row per real LLM call: the 21-argument constructor writes every column, the pre-C1
 * 20-argument one records a null {@code agent_version_id} as an honest "unknown", and {@code
 * reasoningTokens} is the only mutable field (null = not reported, never a fabricated 0).
 */
class LlmCallLogTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final UUID VERSION = UUID.randomUUID();
    private static final Instant STARTED = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void theFullConstructorPopulatesEveryGetter() {
        LlmCallLog log =
                new LlmCallLog(
                        AGENT,
                        "s-1",
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-5",
                        100L,
                        50L,
                        150L,
                        new BigDecimal("0.002500"),
                        false,
                        "boom",
                        "req-9",
                        "IllegalStateException",
                        "trace...",
                        42L,
                        STARTED,
                        "exp=a",
                        "t".repeat(32),
                        "s".repeat(16),
                        7L,
                        11L,
                        VERSION);

        assertNull(log.getId(), "the id is database-generated");
        assertEquals(AGENT, log.getAgentId());
        assertEquals("s-1", log.getSessionId());
        assertEquals(ProviderName.ANTHROPIC, log.getProviderName());
        assertEquals("claude-sonnet-5", log.getModel());
        assertEquals(100L, log.getPromptTokens());
        assertEquals(50L, log.getCompletionTokens());
        assertEquals(150L, log.getTotalTokens());
        assertEquals(new BigDecimal("0.002500"), log.getEstimatedCostUsd());
        assertEquals("boom", log.getErrorMessage());
        assertEquals("req-9", log.getRequestId());
        assertEquals("IllegalStateException", log.getExceptionType());
        assertEquals("trace...", log.getStackTrace());
        assertEquals(42L, log.getLatencyMs());
        assertEquals(STARTED, log.getStartedAt());
        assertEquals("exp=a", log.getExperimentVariants());
        assertEquals("t".repeat(32), log.getTraceId());
        assertEquals("s".repeat(16), log.getSpanId());
        assertEquals(7L, log.getCacheCreationTokens());
        assertEquals(11L, log.getCacheReadTokens());
        assertEquals(VERSION, log.getAgentVersionId());
        assertFalse(log.isSuccess());
    }

    @Test
    void theLegacyConstructorLeavesTheVersionPinNull() {
        LlmCallLog log =
                new LlmCallLog(
                        null,
                        null,
                        ProviderName.OPENAI,
                        "gpt-5",
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        null,
                        null,
                        null,
                        0L,
                        STARTED,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertNull(log.getAgentId());
        assertNull(log.getSessionId());
        assertNull(log.getPromptTokens());
        assertNull(log.getEstimatedCostUsd());
        assertTrue(log.isSuccess());
        assertNull(log.getAgentVersionId(), "the pre-C1 signature records an honest unknown");
        assertNull(log.getReasoningTokens());
    }

    @Test
    void reasoningTokensIsTheOnlyMutableField() {
        LlmCallLog log =
                new LlmCallLog(
                        AGENT,
                        null,
                        ProviderName.OPENAI,
                        "gpt-5",
                        null,
                        null,
                        null,
                        null,
                        true,
                        null,
                        null,
                        null,
                        null,
                        1L,
                        STARTED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertNull(log.getReasoningTokens());
        log.setReasoningTokens(640L);
        assertEquals(640L, log.getReasoningTokens());
    }
}
