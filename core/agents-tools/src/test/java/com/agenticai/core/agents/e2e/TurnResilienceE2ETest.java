package com.agenticai.core.agents.e2e;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.MessageRole;
import com.agenticai.core.agents.turn.cache.LlmResponseCacheService;
import com.agenticai.core.chat.UpstreamProviderException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;

/**
 * Timeouts, failover and retries, run through the real turn pipeline (the real {@code AgentChatService},
 * ChatClient, Spring AI's tool loop, the production tool wrapper chain, session memory); only the LLM
 * provider is scripted. Durations are real but small (a few hundred milliseconds).
 *
 * <p>What these pin: a tool that outlives the stream idle timeout used to kill the turn and replay it
 * on another provider; a buffered turn that ran out of time threw its tool work away and answered at FAST
 * depth with no tools and no tool results; a provider failure after a send tool ran replayed the turn,
 * and the send, on the fallback; a transient 429/5xx before the first token was never retried.
 */
class TurnResilienceE2ETest {

    private static final String GOAL = "Email the customer their invoice.";

    private final LlmResponseCacheService cache = mock(LlmResponseCacheService.class);
    private ApprovalTurnHarness harness;

    @AfterEach
    void close() {
        if (harness != null) {
            harness.close();
        }
    }

    private ApprovalTurnHarness.Builder base(ScriptedLlm.Responder responder) {
        return ApprovalTurnHarness.builder().responder(responder).responseCache(cache).realErrorTranslation();
    }

    private static RecordingTool sendEmail() {
        return RecordingTool.returning("send_email", "Sends an email", "{\"sent\":true}");
    }

    private static RecordingTool getOrder() {
        return RecordingTool.returning("get_order", "Reads an order", "{\"id\":7}");
    }

    /** A sensible model: calls {@code tool} until it has a result, then answers. */
    private static ScriptedLlm.Reply callThenAnswer(ScriptedLlm.Request request, String tool, String answer) {
        return request.hasToolResult()
                ? ScriptedLlm.text(answer)
                : ScriptedLlm.callTool("c" + request.number(), tool, "{\"to\":\"a@b.c\"}");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Blocks until the thread is interrupted (the buffered turn's timeout cancels it). */
    private static void hang() {
        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private ChatTurn lastAssistantTurn() {
        List<ChatTurn> stored = harness.storedTranscript();
        for (int i = stored.size() - 1; i >= 0; i--) {
            if (stored.get(i).role() == MessageRole.ASSISTANT) {
                return stored.get(i);
            }
        }
        throw new AssertionError("no assistant turn stored: " + stored);
    }

    // ----------------------------------------------------------- 1. idle timeout vs tool time

    @Test
    void aToolThatRunsLongerThanTheStreamIdleTimeoutDoesNotKillTheTurn() {
        RecordingTool slow = new RecordingTool("get_order", "Reads an order", args -> {
            sleep(700); // 7x the idle timeout below
            return "{\"id\":7}";
        });
        harness = base(request -> callThenAnswer(request, "get_order", "Order 7 is shipped."))
                .tool(slow)
                .timeouts(Duration.ofMillis(100), Duration.ofSeconds(240))
                .build();

        String reply = harness.chat("Where is order 7?");

        assertAll(
                () -> assertEquals("Order 7 is shipped.", reply),
                () -> assertEquals(1, slow.executions()),
                () -> assertEquals(2, harness.llm().modelCalls()));
    }

    @Test
    void aToolThatNeverReturnsIsStillCaughtAtTheToolCeiling() {
        RecordingTool hung = new RecordingTool("get_order", "Reads an order", args -> {
            sleep(3_000);
            return "{}";
        });
        harness = base(request -> callThenAnswer(request, "get_order", "unreachable"))
                .tool(hung)
                .timeouts(Duration.ofMillis(100), Duration.ofSeconds(240))
                .resilience(r -> r.setToolIdleCeiling(Duration.ofMillis(400)))
                .build();

        long started = System.nanoTime();
        Throwable failure = harness.chatFailure("Where is order 7?");
        long tookMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertNotNull(failure, "a hung tool must still end the turn");
        assertInstanceOf(UpstreamProviderException.class, failure);
        assertEquals(408, ((UpstreamProviderException) failure).getUpstreamStatus());
        assertTrue(tookMillis < 2_500, "gave up at the ceiling, not when the tool returned: " + tookMillis + "ms");
    }

    // -------------------------------------------------- 2. the buffered turn's time limit

    @Test
    void aBufferedTurnThatRunsOutOfTimeAfterToolsRanReportsWhatRanInsteadOfAnsweringWithoutThem() {
        RecordingTool email = sendEmail();
        harness = base(request -> {
                    if (request.number() == 1) {
                        return ScriptedLlm.callTool("c1", "send_email", "{\"to\":\"a@b.c\"}");
                    }
                    if (request.number() == 2) {
                        hang(); // the model never answers after the tool result
                    }
                    // What the old fallback got: a FAST re-answer with no tools and no tool results.
                    return ScriptedLlm.text("I have sent the email.");
                })
                .tool(email)
                .depth(ReasoningDepth.CAREFUL)
                .timeouts(Duration.ofSeconds(90), Duration.ofMillis(600))
                .build();

        String reply = harness.chat(GOAL);

        assertAll(
                () -> assertEquals(1, email.executions(), "the send is neither repeated nor forgotten"),
                () -> assertEquals(2, harness.llm().modelCalls(), "no second answer written without the tool result"),
                () -> assertFalse(reply.contains("I have sent the email"), reply),
                () -> assertTrue(reply.contains("send_email"), "the person is told what ran: " + reply),
                () -> assertTrue(reply.contains("-second limit"), reply),
                () -> verify(cache, never()).put(any(), any(), any(), any(), any(), any()),
                () -> assertTrue(lastAssistantTurn().content().contains("send_email"),
                        "the next turn can see what ran: " + lastAssistantTurn().content()));
    }

    @Test
    void theTimeoutNoticeComesFromConfiguration() {
        harness = base(request -> {
                    if (request.number() == 1) {
                        return ScriptedLlm.callTool("c1", "send_email", "{\"to\":\"a@b.c\"}");
                    }
                    hang();
                    return ScriptedLlm.text("unreachable");
                })
                .tool(sendEmail())
                .depth(ReasoningDepth.CAREFUL)
                .timeouts(Duration.ofSeconds(90), Duration.ofMillis(600))
                .resilience(r -> r.setDepthTimeoutPartialNotice("<<{depth}|{completed}|{running}>>"))
                .build();

        assertEquals("<<carefully|send_email|none>>".replace("carefully", "careful"), harness.chat(GOAL));
    }

    @Test
    void aBufferedTurnThatRunsOutOfTimeBeforeAnyToolRanIsAnsweredAtFastDepthWithItsToolsStillOffered() {
        RecordingTool order = getOrder();
        harness = base(request -> {
                    if (request.number() == 1) {
                        hang(); // the model stalls before doing anything
                    }
                    return ScriptedLlm.text("Order 7 is shipped.");
                })
                .tool(order)
                .depth(ReasoningDepth.CAREFUL)
                .timeouts(Duration.ofSeconds(90), Duration.ofMillis(400))
                .build();

        String reply = harness.chat("Where is order 7?");

        assertAll(
                () -> assertEquals("Order 7 is shipped.", reply),
                () -> assertEquals(2, harness.llm().modelCalls()),
                () -> assertFalse(harness.llm().requests().get(1).offeredTools().isEmpty(),
                        "nothing had run, so nothing is lost by keeping the tools: "
                                + harness.llm().requests().get(1).offeredTools()));
    }

    @Test
    void withTheFastRetryTurnedOffATimeoutBeforeAnyToolIsReportedAsATimeout() {
        harness = base(request -> {
                    hang();
                    return ScriptedLlm.text("unreachable");
                })
                .tool(getOrder())
                .depth(ReasoningDepth.CAREFUL)
                .timeouts(Duration.ofSeconds(90), Duration.ofMillis(300))
                .resilience(r -> r.setRetryAtFastWhenNoToolRan(false))
                .build();

        String reply = harness.chat("Where is order 7?");

        assertAll(
                () -> assertEquals(1, harness.llm().modelCalls()),
                () -> assertTrue(reply.contains("-second limit"), reply));
    }

    @Test
    void aJobsLargerDeadlineIsNotCutOffAtTheBufferedTurnTimeout() {
        harness = base(request -> {
                    if (request.number() == 1) {
                        sleep(900); // 3x the buffered-turn timeout below, well inside the job's deadline
                        return ScriptedLlm.text("The long answer.");
                    }
                    return ScriptedLlm.text("The fallback answer.");
                })
                .tool(getOrder())
                .depth(ReasoningDepth.CAREFUL)
                .timeouts(Duration.ofSeconds(90), Duration.ofMillis(300))
                .build();

        String reply = harness.chatWithDeadline("Summarise every order.", Duration.ofSeconds(30));

        assertAll(
                () -> assertEquals("The long answer.", reply),
                () -> assertEquals(1, harness.llm().modelCalls()));
    }

    // -------------------------------------------------------------- 3. failover after tools ran

    /** The model fails on its first request that carries a tool result, once. */
    private static ScriptedLlm.Responder failsOnceAfterTheToolResult(String tool, RuntimeException failure) {
        AtomicInteger failures = new AtomicInteger();
        return request -> {
            if (request.hasToolResult() && failures.getAndIncrement() == 0) {
                throw failure;
            }
            return callThenAnswer(request, tool, "Done.");
        };
    }

    @Test
    void aProviderFailureAfterASendToolRanIsNotReplayedOnTheFallbackProvider() {
        RecordingTool email = sendEmail();
        harness = base(failsOnceAfterTheToolResult("send_email", new TransientAiException("503 - overloaded")))
                .tool(email)
                .fallbackProvider()
                .retry(r -> r.setMaxAttempts(1))
                .build();

        Throwable failure = harness.chatFailure(GOAL);

        assertAll(
                () -> assertNotNull(failure, "the person is told it failed rather than the email going out twice"),
                () -> assertEquals(1, email.executions(), "the fallback would have run the turn, and the send, again"),
                () -> assertEquals(2, harness.llm().modelCalls()));
    }

    @Test
    void aProviderFailureAfterOnlyReadsRanStillFailsOverToTheFallbackProvider() {
        RecordingTool order = getOrder();
        harness = base(failsOnceAfterTheToolResult("get_order", new TransientAiException("503 - overloaded")))
                .tool(order)
                .fallbackProvider()
                .retry(r -> r.setMaxAttempts(1))
                .build();

        String reply = harness.chat("Where is order 7?");

        assertAll(
                () -> assertEquals("Done.", reply),
                () -> assertEquals(2, order.executions(), "a read can safely run again on the fallback"));
    }

    @Test
    void theSameHoldsAtABufferedDepth() {
        RecordingTool email = sendEmail();
        harness = base(failsOnceAfterTheToolResult("send_email", new TransientAiException("503 - overloaded")))
                .tool(email)
                .fallbackProvider()
                .depth(ReasoningDepth.CAREFUL)
                .retry(r -> r.setMaxAttempts(1))
                .build();

        Throwable failure = harness.chatFailure(GOAL);

        assertAll(
                () -> assertNotNull(failure),
                () -> assertEquals(1, email.executions()));
    }

    @Test
    void aProviderFailureBeforeAnyToolRanFailsOverAsBefore() {
        RecordingTool email = sendEmail();
        AtomicInteger calls = new AtomicInteger();
        harness = base(request -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new TransientAiException("503 - overloaded");
                    }
                    return callThenAnswer(request, "send_email", "Sent.");
                })
                .tool(email)
                .fallbackProvider()
                .retry(r -> r.setMaxAttempts(1))
                .build();

        assertEquals("Sent.", harness.chat(GOAL));
        assertEquals(1, email.executions());
    }

    // ------------------------------------------------------ 4. retry before the first token

    @Test
    void aTransientFailureBeforeTheFirstTokenIsRetriedOnTheSameProvider() {
        AtomicInteger calls = new AtomicInteger();
        harness = base(request -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new TransientAiException("503 - overloaded");
                    }
                    return ScriptedLlm.text("Here you go.");
                })
                .retry(r -> r.setMaxAttempts(3))
                .build();

        assertEquals("Here you go.", harness.chat("Hello"));
        assertEquals(3, harness.llm().modelCalls());
    }

    @Test
    void aRateLimitIsRetriedToo() {
        AtomicInteger calls = new AtomicInteger();
        harness = base(request -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new NonTransientAiException("429 - {\"error\":{\"message\":\"Rate limit. Try again in 20ms.\"}}");
                    }
                    return ScriptedLlm.text("Here you go.");
                })
                .retry(r -> r.setMaxAttempts(2))
                .build();

        assertEquals("Here you go.", harness.chat("Hello"));
        assertEquals(2, harness.llm().modelCalls());
    }

    @Test
    void retriesStopAtTheConfiguredAttemptsAndTheFailureIsReported() {
        harness = base(request -> {
                    throw new TransientAiException("503 - overloaded");
                })
                .retry(r -> r.setMaxAttempts(3))
                .build();

        Throwable failure = harness.chatFailure("Hello");

        assertNotNull(failure);
        assertEquals(3, harness.llm().modelCalls());
    }

    @Test
    void aClientErrorOrAnOverlongRetryAfterIsNotRetried() {
        harness = base(request -> {
                    throw new NonTransientAiException("400 - invalid request");
                })
                .retry(r -> r.setMaxAttempts(3))
                .build();
        assertNotNull(harness.chatFailure("Hello"));
        assertEquals(1, harness.llm().modelCalls());
        harness.close();

        harness = base(request -> {
                    throw new NonTransientAiException("429 - Please try again in 600s.");
                })
                .retry(r -> r.setMaxAttempts(3))
                .build();
        assertNotNull(harness.chatFailure("Hello"));
        assertEquals(1, harness.llm().modelCalls(), "the provider asks for 10 minutes; the fallback is the better use of the time");
    }

    @Test
    void aStreamIsNotRetriedOnceATookToolHasRun() {
        RecordingTool email = sendEmail();
        harness = base(failsOnceAfterTheToolResult("send_email", new TransientAiException("503 - overloaded")))
                .tool(email)
                .retry(r -> r.setMaxAttempts(3))
                .build();

        Throwable failure = harness.chatFailure(GOAL);

        assertAll(
                () -> assertNotNull(failure),
                () -> assertEquals(1, email.executions(), "a retry would run the tools of the first attempt again"),
                () -> assertEquals(2, harness.llm().modelCalls()));
    }

    @Test
    void retryingBeforeTheFirstTokenCanBeSwitchedOff() {
        harness = base(request -> {
                    throw new TransientAiException("503 - overloaded");
                })
                .retry(r -> {
                    r.setMaxAttempts(3);
                    r.setStreamPreFirstToken(false);
                })
                .build();

        assertNotNull(harness.chatFailure("Hello"));
        assertEquals(1, harness.llm().modelCalls());
    }
}
