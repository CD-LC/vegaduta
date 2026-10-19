package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.opentelemetry.api.trace.Span;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

/**
 * Stream-path behaviour of {@link LlmMetricsAdvisor#adviseStream}: every
 * terminal signal - complete, error, and CANCEL (client disconnect or the
 * stream-idle {@code .timeout()} in ChatTurnExecutor) - writes exactly one
 * {@code llm_call_log} row, with usage merged across chunks rather than read
 * off the last one. Before 2026-09-28 a cancelled stream wrote nothing and
 * its billed tokens vanished from cost and budget accounting.
 */
class LlmMetricsAdvisorStreamTest {

    private final LlmCallLogService callLogService = mock(LlmCallLogService.class);
    private final StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final LlmMetricsAdvisor advisor = new LlmMetricsAdvisor(
        new LlmCallMetricsService(), callLogService, ProviderName.ANTHROPIC, "claude-sonnet-4-5",
        meterRegistry, new LlmPricingCatalog());

    private static ChatClientRequest request() {
        return new ChatClientRequest(new Prompt("hi"), Map.of());
    }

    private static ChatClientResponse chunk(Usage usage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (usage != null) {
            metadata.usage(usage);
        }
        ChatResponse chatResponse = new ChatResponse(
            List.of(new Generation(new AssistantMessage("tok"))), metadata.build());
        return new ChatClientResponse(chatResponse, Map.of());
    }

    /** Captured arguments of the single 20-arg {@link LlmCallLogService#record} call. */
    private record Recorded(Long promptTokens, Long completionTokens, boolean success, String errorMessage, String exceptionType) {}

    private Recorded recordedOnce() {
        ArgumentCaptor<Long> prompt = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> completion = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Boolean> success = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> exceptionType = ArgumentCaptor.forClass(String.class);
        verify(callLogService, timeout(2000).times(1)).record(
            any(), any(), any(), any(),
            prompt.capture(), completion.capture(), any(),
            success.capture(), errorMessage.capture(), any(), exceptionType.capture(), any(),
            anyLong(), any(), any(), any(), any(),
            any(), any(), any(), any(), any());
        return new Recorded(prompt.getValue(), completion.getValue(), success.getValue(),
            errorMessage.getValue(), exceptionType.getValue());
    }

    private double counter(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    void completedStreamMergesInputTokensFromAnEarlyChunkWithOutputTokensFromALateOne() {
        // Anthropic's shape: message_start carries input tokens, the closing
        // message_delta carries output tokens, text chunks carry neither.
        when(chain.nextStream(any())).thenReturn(Flux.just(
            chunk(new DefaultUsage(1200, 0)),
            chunk(new EmptyUsage()),
            chunk(null),
            chunk(new DefaultUsage(0, 340))));

        advisor.adviseStream(request(), chain).blockLast();

        Recorded row = recordedOnce();
        assertTrue(row.success());
        assertEquals(1200L, row.promptTokens(), "input tokens from the early chunk must survive the late one");
        assertEquals(340L, row.completionTokens());
        assertNull(row.errorMessage());
        assertEquals(1.0, counter("llm.calls"));
        assertEquals(0.0, counter("llm.call.errors"));
        assertTrue(counter("llm.cost.usd") > 0.0, "a completed stream with usage must be priced");
    }

    @Test
    void cumulativeUsageTakesTheMaximumNotTheSum() {
        // OpenAI-compatible providers that repeat running totals per chunk.
        when(chain.nextStream(any())).thenReturn(Flux.just(
            chunk(new DefaultUsage(500, 10)),
            chunk(new DefaultUsage(500, 60)),
            chunk(new DefaultUsage(500, 90))));

        advisor.adviseStream(request(), chain).blockLast();

        Recorded row = recordedOnce();
        assertEquals(500L, row.promptTokens());
        assertEquals(90L, row.completionTokens());
    }

    @Test
    void cancelledStreamIsLoggedOnceWithTheUsageSeenSoFar() {
        when(chain.nextStream(any())).thenReturn(Flux.just(
            chunk(new DefaultUsage(800, 0)),
            chunk(new DefaultUsage(800, 25)),
            chunk(new DefaultUsage(800, 400))));

        // take(2) cancels the upstream after the second chunk - the same
        // signal a client disconnect delivers.
        advisor.adviseStream(request(), chain).take(2).blockLast();

        Recorded row = recordedOnce();
        assertFalse(row.success(), "a cancelled call must not be recorded as a completed one");
        assertEquals(CancellationException.class.getName(), row.exceptionType());
        assertNotNull(row.errorMessage());
        assertTrue(row.errorMessage().startsWith(LlmMetricsAdvisor.STREAM_CANCELLED_MESSAGE_PREFIX), row.errorMessage());
        assertEquals(800L, row.promptTokens());
        assertEquals(25L, row.completionTokens(), "only usage actually observed before the cancel");
        assertEquals(1.0, counter("llm.stream.cancelled"));
        assertEquals(0.0, counter("llm.call.errors"), "a consumer cancel is not a provider error");
        assertTrue(counter("llm.cost.usd") > 0.0, "tokens billed before a cancel must still be costed");
    }

    @Test
    void cancelOutcomeIsRecordedOnANonInterruptedThreadAndTheInterruptIsRestored() {
        // SE-12: disposing a scheduler task interrupts the worker, and the cancel signal then runs with the
        // interrupt flag set - JDBC / EntityManager work fails on such a thread, losing the row.
        java.util.concurrent.atomic.AtomicBoolean interruptedWhileRecording = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(inv -> {
            interruptedWhileRecording.set(Thread.currentThread().isInterrupted());
            return null;
        }).when(callLogService).record(
            any(), any(), any(), any(),
            any(), any(), any(),
            anyBoolean(), any(), any(), any(), any(),
            anyLong(), any(), any(), any(), any(),
            any(), any(), any(), any(), any());
        when(chain.nextStream(any())).thenReturn(Flux.just(
            chunk(new DefaultUsage(800, 0)),
            chunk(new DefaultUsage(800, 25)),
            chunk(new DefaultUsage(800, 400))));

        Thread.currentThread().interrupt();
        boolean stillInterrupted;
        try {
            // Flux.just is synchronous, so the take(2) cancel runs on this (interrupted) thread.
            advisor.adviseStream(request(), chain).take(2).subscribe();
            stillInterrupted = Thread.currentThread().isInterrupted();
        } finally {
            Thread.interrupted();
        }

        recordedOnce();
        assertFalse(interruptedWhileRecording.get(), "the outcome must be recorded with the interrupt flag cleared");
        assertTrue(stillInterrupted, "the caller's interrupt must be restored after recording");
    }

    @Test
    void streamIdleTimeoutDownstreamCancelsUpstreamAndIsLogged() {
        // ChatTurnExecutor applies .timeout(streamIdleTimeout) downstream of
        // this advisor: on expiry it cancels the upstream (us) and errors
        // downstream.
        when(chain.nextStream(any())).thenReturn(
            Flux.concat(Flux.just(chunk(new DefaultUsage(300, 0))), Flux.never()));

        assertThrows(RuntimeException.class, () ->
            advisor.adviseStream(request(), chain).timeout(Duration.ofMillis(100)).blockLast());

        Recorded row = recordedOnce();
        assertFalse(row.success());
        assertEquals(CancellationException.class.getName(), row.exceptionType());
        assertEquals(300L, row.promptTokens());
    }

    @Test
    void disposingAnInFlightSubscriptionLogsExactlyOnce() {
        when(chain.nextStream(any())).thenReturn(Flux.never());

        var subscription = advisor.adviseStream(request(), chain).subscribe();
        subscription.dispose();
        subscription.dispose();

        Recorded row = recordedOnce();
        assertFalse(row.success());
        assertNull(row.promptTokens(), "no usage seen is recorded as absent, never a fabricated zero");
    }

    @Test
    void erroredStreamKeepsTheUsageStreamedBeforeTheFailure() {
        when(chain.nextStream(any())).thenReturn(Flux.concat(
            Flux.just(chunk(new DefaultUsage(700, 0))),
            Flux.error(new IllegalStateException("upstream reset"))));

        assertThrows(IllegalStateException.class, () -> advisor.adviseStream(request(), chain).blockLast());

        Recorded row = recordedOnce();
        assertFalse(row.success());
        assertEquals(IllegalStateException.class.getName(), row.exceptionType());
        assertTrue(row.errorMessage().contains("upstream reset"));
        assertEquals(700L, row.promptTokens());
        assertEquals(1.0, counter("llm.call.errors"));
        assertEquals(0.0, counter("llm.stream.cancelled"));
    }

    @Test
    void eachSubscriptionIsItsOwnLoggedCall() {
        when(chain.nextStream(any())).thenReturn(Flux.just(chunk(new DefaultUsage(10, 5))));

        Flux<ChatClientResponse> stream = advisor.adviseStream(request(), chain);
        stream.blockLast();
        stream.blockLast();

        verify(callLogService, times(2)).record(
            any(), any(), any(), any(), any(), any(), any(),
            anyBoolean(), any(), any(), any(), any(),
            anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // --- span resolution -------------------------------------------------------

    @Test
    void spanFallsBackToTheCallerSpanWhenNoObservationIsInTheReactorContext() {
        Span caller = Span.getInvalid();
        assertSame(caller, LlmMetricsAdvisor.resolveStreamSpan(Context.empty(), caller));
    }

    @Test
    void noopObservationInTheReactorContextFallsBackToTheCallerSpan() {
        Span caller = Span.getInvalid();
        Context ctx = Context.of(LlmMetricsAdvisor.OBSERVATION_CONTEXT_KEY, Observation.NOOP);
        assertSame(caller, LlmMetricsAdvisor.resolveStreamSpan(ctx, caller));
    }

    @Test
    void observationWhoseScopeYieldsNoValidSpanFallsBackToTheCallerSpan() {
        Observation observation = mock(Observation.class);
        when(observation.isNoop()).thenReturn(false);
        when(observation.openScope()).thenReturn(Observation.Scope.NOOP);
        Span caller = Span.getInvalid();
        Context ctx = Context.of(LlmMetricsAdvisor.OBSERVATION_CONTEXT_KEY, observation);
        assertSame(caller, LlmMetricsAdvisor.resolveStreamSpan(ctx, caller));
    }

    @Test
    void observationThatThrowsNeverBreaksTheStream() {
        Observation observation = mock(Observation.class);
        when(observation.isNoop()).thenReturn(false);
        when(observation.openScope()).thenThrow(new IllegalStateException("boom"));
        Span caller = Span.getInvalid();
        Context ctx = Context.of(LlmMetricsAdvisor.OBSERVATION_CONTEXT_KEY, observation);
        assertSame(caller, LlmMetricsAdvisor.resolveStreamSpan(ctx, caller));
    }

    // --- usage accumulator -----------------------------------------------------

    @Test
    void accumulatorReturnsTheOriginalUsageObjectWhenOneChunkHoldsBothMaxima() {
        // Keeps provider subtypes (GoogleGenAiUsage carries its cache count
        // as its own getters) intact for CacheTokenAccounting.
        LlmMetricsAdvisor.StreamUsageAccumulator acc = new LlmMetricsAdvisor.StreamUsageAccumulator();
        Usage fin = new DefaultUsage(100, 20, 120, "native-final");
        acc.accept(chunk(new DefaultUsage(100, 3)));
        acc.accept(chunk(fin));
        assertSame(fin, acc.merged());
    }

    @Test
    void accumulatorCarriesTheInputChunksNativeUsageWhenMerging() {
        LlmMetricsAdvisor.StreamUsageAccumulator acc = new LlmMetricsAdvisor.StreamUsageAccumulator();
        acc.accept(chunk(new DefaultUsage(900, 0, 900, "native-start")));
        acc.accept(chunk(new DefaultUsage(0, 45, 45, "native-delta")));
        Usage merged = acc.merged();
        assertEquals(900, merged.getPromptTokens());
        assertEquals(45, merged.getCompletionTokens());
        assertEquals(945, merged.getTotalTokens());
        assertEquals("native-start", merged.getNativeUsage(),
            "cache read/write counts ride on the input-side chunk's native usage");
    }

    @Test
    void accumulatorWithNoUsageReturnsNull() {
        LlmMetricsAdvisor.StreamUsageAccumulator acc = new LlmMetricsAdvisor.StreamUsageAccumulator();
        acc.accept(chunk(null));
        acc.accept(chunk(new EmptyUsage()));
        acc.accept(null);
        assertNull(acc.merged());
    }
}
