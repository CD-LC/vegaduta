package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

/**
 * {@link LlmRetry} is the seam for the spring-retry to core-retry move; these pin the behaviour the
 * old inline {@code LlmModelSupport} template had: attempt counts, transient-only retry, the
 * per-attempt warning (the "RetryListener metric"), and that the Spring AI adapter is the same
 * policy.
 */
class LlmRetryTest {

    private static LlmRetry retry(int attempts) {
        return new SpringRetryLlmRetry(attempts, Duration.ofMillis(1), Duration.ofMillis(2));
    }

    @Test
    void aRefusedModelServerUrlIsNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                LlmHttpClientFactory.ModelServerUrlNotAllowedException.class,
                () ->
                        retry(5).execute(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new LlmHttpClientFactory
                                                    .ModelServerUrlNotAllowedException(
                                                    "not allowed", null);
                                        }));
        assertEquals(1, calls.get());
    }

    @Test
    void transientFailuresUseExactlyTheConfiguredAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                TransientAiException.class,
                () ->
                        retry(3).execute(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new TransientAiException("503");
                                        }));
        assertEquals(3, calls.get());
    }

    @Test
    void nonTransientFailuresAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                NonTransientAiException.class,
                () ->
                        retry(3).execute(
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new NonTransientAiException("429");
                                        }));
        assertEquals(1, calls.get());
    }

    @Test
    void successAfterATransientFailureReturnsTheValue() {
        AtomicInteger calls = new AtomicInteger();
        String out =
                retry(3).execute(
                                () -> {
                                    if (calls.incrementAndGet() < 2) {
                                        throw new TransientAiException("500");
                                    }
                                    return "ok";
                                });
        assertEquals("ok", out);
        assertEquals(2, calls.get());
    }

    @Test
    void degenerateSettingsClampToOneAttemptAndValidBackoff() {
        LlmRetry degenerate = new SpringRetryLlmRetry(0, Duration.ZERO, Duration.ZERO);
        assertEquals(1, degenerate.maxAttempts());
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                TransientAiException.class,
                () ->
                        degenerate.execute(
                                () -> {
                                    calls.incrementAndGet();
                                    throw new TransientAiException("500");
                                }));
        assertEquals(1, calls.get());
    }

    @Test
    void springAiAdapterIsTheSamePolicyAndIsStable() {
        LlmRetry retry = retry(2);
        assertSame(retry.forSpringAi(), retry.forSpringAi());
        AtomicInteger calls = new AtomicInteger();
        assertThrows(
                TransientAiException.class,
                () ->
                        retry.forSpringAi()
                                .execute(
                                        ctx -> {
                                            calls.incrementAndGet();
                                            throw new TransientAiException("500");
                                        }));
        assertEquals(2, calls.get());
    }

    @Test
    void eachTransientAttemptIsLoggedAndNonTransientIsNot() {
        Logger logger = (Logger) LoggerFactory.getLogger(SpringRetryLlmRetry.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThrows(
                    TransientAiException.class,
                    () ->
                            retry(3).execute(
                                            () -> {
                                                throw new TransientAiException("503");
                                            }));
            long warns =
                    appender.list.stream()
                            .filter(
                                    e ->
                                            e.getLevel() == Level.WARN
                                                    && e.getFormattedMessage()
                                                            .startsWith("LLM call attempt"))
                            .count();
            assertEquals(3, warns);

            appender.list.clear();
            assertThrows(
                    NonTransientAiException.class,
                    () ->
                            retry(3).execute(
                                            () -> {
                                                throw new NonTransientAiException("400");
                                            }));
            assertTrue(appender.list.isEmpty());
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void modelSupportDelegatesToTheSamePolicy() {
        assertFalse(LlmModelSupport.isTransient(new IllegalStateException("bug")));
        assertTrue(LlmModelSupport.isTransient(new TransientAiException("500")));
        assertEquals(2, LlmModelSupport.defaultRetry().maxAttempts());
    }
}
