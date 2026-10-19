package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

class EffectJournalTest {

    /** Mutable clock for TTL tests. */
    private static final class TestClock extends Clock {
        final AtomicLong now = new AtomicLong(1_000_000L);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    }

    @Test
    void keyIsStableAndSensitiveToEveryPart() {
        String k = EffectJournal.keyFor("run", "step", "send_email", "{\"a\":1}");
        assertEquals(k, EffectJournal.keyFor("run", "step", "send_email", "{\"a\":1}"));
        assertNotEquals(k, EffectJournal.keyFor("run2", "step", "send_email", "{\"a\":1}"));
        assertNotEquals(k, EffectJournal.keyFor("run", "step2", "send_email", "{\"a\":1}"));
        assertNotEquals(k, EffectJournal.keyFor("run", "step", "other", "{\"a\":1}"));
        assertNotEquals(k, EffectJournal.keyFor("run", "step", "send_email", "{\"a\":2}"));
    }

    @Test
    void recordsAndExpiresAfterTtl() {
        TestClock clock = new TestClock();
        EffectJournal j = new EffectJournal(true, Duration.ofMinutes(30), 10, null, clock);
        j.record("k", "sent");
        assertEquals("sent", j.lookup("k").orElseThrow());
        clock.now.addAndGet(Duration.ofMinutes(31).toMillis());
        assertTrue(j.lookup("k").isEmpty());
    }

    @Test
    void boundedLruEvictsOldest() {
        EffectJournal j = new EffectJournal(true, Duration.ofMinutes(30), 2, null, new TestClock());
        j.record("a", "1");
        j.record("b", "2");
        j.record("c", "3");
        assertTrue(j.lookup("a").isEmpty());
        assertTrue(j.lookup("c").isPresent());
    }

    @Test
    void disabledJournalNeverRecords() {
        EffectJournal j =
                new EffectJournal(false, Duration.ofMinutes(30), 2, null, new TestClock());
        j.record("a", "1");
        assertFalse(j.lookup("a").isPresent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void loggingCallbackServesSecondIdenticalDurableCallFromJournal() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("send_email")
                                .description("d")
                                .inputSchema("{}")
                                .build());
        when(delegate.call(anyString())).thenReturn("sent #1");
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        EffectJournal journal =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, new TestClock());
        LoggingToolCallback cb =
                new LoggingToolCallback(
                        delegate,
                        mock(ToolCallLogRepository.class),
                        UUID.randomUUID(),
                        "session",
                        issues,
                        mock(ToolCircuitBreakerService.class),
                        UUID.randomUUID(),
                        journal,
                        name -> true,
                        "turn1",
                        null);

        assertEquals("sent #1", cb.call("{\"to\":\"x\"}"));
        assertEquals("sent #1", cb.call("{\"to\":\"x\"}"));
        verify(delegate, times(1)).call("{\"to\":\"x\"}");

        cb.call("{\"to\":\"y\"}");
        verify(delegate, times(1)).call("{\"to\":\"y\"}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonDurableToolAndNotExecutedResultsAreNotJournaled() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("t")
                                .description("d")
                                .inputSchema("{}")
                                .build());
        when(delegate.call(anyString()))
                .thenReturn("This action ('t') requires human approval before it can run");
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        EffectJournal journal =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, new TestClock());
        LoggingToolCallback durable =
                new LoggingToolCallback(
                        delegate,
                        mock(ToolCallLogRepository.class),
                        UUID.randomUUID(),
                        "s",
                        issues,
                        mock(ToolCircuitBreakerService.class),
                        UUID.randomUUID(),
                        journal,
                        name -> true,
                        "turn",
                        null);
        durable.call("{}");
        durable.call("{}");
        verify(delegate, times(2)).call("{}");

        LoggingToolCallback pure =
                new LoggingToolCallback(
                        delegate,
                        mock(ToolCallLogRepository.class),
                        UUID.randomUUID(),
                        "s2",
                        issues,
                        mock(ToolCircuitBreakerService.class),
                        UUID.randomUUID(),
                        journal,
                        name -> false,
                        "turn",
                        null);
        pure.call("{}");
        pure.call("{}");
        verify(delegate, times(4)).call("{}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisPathWritesWithTtlAndServesAcrossInstances() {
        org.springframework.data.redis.core.StringRedisTemplate redis =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        EffectJournal a =
                new EffectJournal(true, Duration.ofMinutes(30), 10, redis, new TestClock());
        a.record("k", "sent");
        verify(ops).set("tool:effect:k", "sent", Duration.ofMinutes(30));

        // A different node (empty memory) finds it in Redis.
        when(ops.get("tool:effect:k")).thenReturn("sent");
        EffectJournal b =
                new EffectJournal(true, Duration.ofMinutes(30), 10, redis, new TestClock());
        assertEquals("sent", b.lookup("k").orElseThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureDegradesToMemory() {
        org.springframework.data.redis.core.StringRedisTemplate redis =
                mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        org.mockito.Mockito.doThrow(new IllegalStateException("down"))
                .when(ops)
                .set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(Duration.class));
        when(ops.get(anyString())).thenThrow(new IllegalStateException("down"));
        EffectJournal j =
                new EffectJournal(true, Duration.ofMinutes(30), 10, redis, new TestClock());
        j.record("k", "sent");
        assertEquals("sent", j.lookup("k").orElseThrow());
        assertTrue(j.lookup("other").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void throwingToolIsNotRecordedSoRetryReachesProvider() {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(
                        ToolDefinition.builder()
                                .name("send_email")
                                .description("d")
                                .inputSchema("{}")
                                .build());
        when(delegate.call(anyString()))
                .thenThrow(new IllegalStateException("provider 500"))
                .thenReturn("sent");
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        EffectJournal journal =
                new EffectJournal(true, Duration.ofMinutes(30), 10, null, new TestClock());
        LoggingToolCallback cb =
                new LoggingToolCallback(
                        delegate,
                        mock(ToolCallLogRepository.class),
                        UUID.randomUUID(),
                        "s",
                        issues,
                        mock(ToolCircuitBreakerService.class),
                        UUID.randomUUID(),
                        journal,
                        name -> true,
                        "turn",
                        null);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> cb.call("{}"));
        assertEquals("sent", cb.call("{}"));
        verify(delegate, times(2)).call("{}");
    }

    @Test
    void guardMessagesAreNotJournaled() {
        assertFalse(
                LoggingToolCallback.wasExecuted("This tool call ('t') was not executed: blocked"));
        assertFalse(
                LoggingToolCallback.wasExecuted(
                        "This tool call ('t') was blocked by the workspace procedure \"p\""));
        assertFalse(LoggingToolCallback.wasExecuted("This tool call failed: boom. Explain this"));
        assertFalse(
                LoggingToolCallback.wasExecuted(
                        "This action ('t') requires human approval before it can run and was NOT executed."));
        assertFalse(
                LoggingToolCallback.wasExecuted(
                        "Tool output from 't' was withheld by a content-safety check"));
        assertFalse(
                LoggingToolCallback.wasExecuted(
                        com.agenticai.core.guardrails.ToolCallOutputValidator
                                        .VALIDATION_FAILURE_PREFIX
                                + "x"));
        assertFalse(LoggingToolCallback.wasExecuted(null));
        assertTrue(LoggingToolCallback.wasExecuted("{\"status\":\"sent\"}"));
    }

    @Test
    void turnIdsAreUniquePerCounterAndStablePerTurn() {
        java.util.concurrent.atomic.AtomicInteger c1 =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger c2 =
                new java.util.concurrent.atomic.AtomicInteger();
        assertEquals(AgentToolGuard.turnIdFor(c1), AgentToolGuard.turnIdFor(c1));
        assertNotEquals(AgentToolGuard.turnIdFor(c1), AgentToolGuard.turnIdFor(c2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void isDurableToolClassifiesAndCaches() {
        com.agenticai.core.agents.tools.classification.ToolClassificationRegistry reg =
                mock(
                        com.agenticai.core.agents.tools.classification.ToolClassificationRegistry
                                .class);
        com.agenticai.core.agents.tools.classification.ToolClassification durable =
                mock(com.agenticai.core.agents.tools.classification.ToolClassification.class);
        when(durable.determinism())
                .thenReturn(com.agenticai.core.agents.tools.classification.ToolDeterminism.DURABLE);
        com.agenticai.core.agents.tools.classification.ToolClassification pure =
                mock(com.agenticai.core.agents.tools.classification.ToolClassification.class);
        when(pure.determinism())
                .thenReturn(com.agenticai.core.agents.tools.classification.ToolDeterminism.PURE);
        when(reg.resolve("send_email"))
                .thenReturn(
                        new com.agenticai.core.agents.tools.classification
                                .ToolClassificationDecision.Classified(durable));
        when(reg.resolve("calc"))
                .thenReturn(
                        new com.agenticai.core.agents.tools.classification
                                .ToolClassificationDecision.Classified(pure));
        ObjectProvider<com.agenticai.core.agents.tools.classification.ToolClassificationRegistry>
                regProvider = mock(ObjectProvider.class);
        when(regProvider.getIfAvailable()).thenReturn(reg);
        AgentToolGovernance gov =
                new AgentToolGovernance(
                        regProvider,
                        mock(ObjectProvider.class),
                        mock(ObjectProvider.class),
                        false,
                        "");

        assertTrue(gov.isDurableTool("send_email"));
        assertTrue(gov.isDurableTool("send_email"));
        assertFalse(gov.isDurableTool("calc"));
        assertFalse(gov.isDurableTool("unknown"));
        verify(reg, times(1)).resolve("send_email");
    }
}
