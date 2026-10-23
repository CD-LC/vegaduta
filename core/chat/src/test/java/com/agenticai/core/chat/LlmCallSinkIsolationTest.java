package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * The observer contract of {@link LlmMetricsAdvisor}: a failing sink (or a failing durable-log
 * write) is isolated from the model call and from the other sinks, and every sink runs under the
 * originating call's tenant even when a stream finishes on another thread.
 */
class LlmCallSinkIsolationTest {

    private final CallAdvisorChain callChain = mock(CallAdvisorChain.class);
    private final StreamAdvisorChain streamChain = mock(StreamAdvisorChain.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private LlmMetricsAdvisor advisor(LlmCallLogService logService) {
        return new LlmMetricsAdvisor(
                new LlmCallMetricsService(),
                logService,
                ProviderName.ANTHROPIC,
                "claude-sonnet-4-5",
                registry,
                new LlmPricingCatalog());
    }

    private static ChatClientRequest request() {
        return new ChatClientRequest(new Prompt("hi"), Map.of());
    }

    private static ChatClientResponse chunk() {
        ChatResponse response =
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok"))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build());
        return new ChatClientResponse(response, Map.of());
    }

    private static final class Boom implements LlmCallSink {
        @Override
        public void onCall(LlmCallObservation observation) {
            throw new IllegalStateException("sink exploded");
        }
    }

    private static final class Recording implements LlmCallSink {
        final List<LlmCallObservation> seen = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<String> tenant = new AtomicReference<>();

        @Override
        public void onCall(LlmCallObservation observation) {
            // Record the tenant BEFORE publishing the observation: the stream test waits for `seen`
            // to become non-empty and then reads `tenant`, so the other order lets it read null.
            tenant.set(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            seen.add(observation);
        }
    }

    @Test
    void failingSinkDoesNotAffectASuccessfulCallOrTheOtherSinks() {
        Recording recording = new Recording();
        ChatClientResponse expected = chunk();
        when(callChain.nextCall(any())).thenReturn(expected);

        ChatClientResponse actual =
                advisor(mock(LlmCallLogService.class))
                        .withSinks(List.of(new Boom(), recording))
                        .adviseCall(request(), callChain);

        assertSame(expected, actual);
        assertEquals(1, recording.seen.size(), "the sink after the failing one still runs");
        assertTrue(recording.seen.get(0).success());
    }

    @Test
    void failingSinkDoesNotReplaceTheModelCallsOwnException() {
        Recording recording = new Recording();
        IllegalArgumentException modelFailure = new IllegalArgumentException("provider down");
        when(callChain.nextCall(any())).thenThrow(modelFailure);

        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                advisor(mock(LlmCallLogService.class))
                                        .withSinks(List.of(new Boom(), recording))
                                        .adviseCall(request(), callChain));

        assertSame(modelFailure, thrown);
        assertEquals(1, recording.seen.size());
        assertEquals(false, recording.seen.get(0).success());
        assertEquals("provider down", recording.seen.get(0).failure().message());
    }

    @Test
    void aThrowingDurableLogWriteDoesNotStopTheMetersOrFailTheCall() {
        LlmCallLogService logService = mock(LlmCallLogService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down"))
                .when(logService)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        ChatClientResponse expected = chunk();
        when(callChain.nextCall(any())).thenReturn(expected);

        ChatClientResponse actual = advisor(logService).adviseCall(request(), callChain);

        assertSame(expected, actual);
        assertEquals(1.0, registry.get("llm.calls").counter().count());
        assertEquals(
                0,
                registry.find("llm.call.errors").meters().size(),
                "a sink failure is not a call error");
    }

    @Test
    void failingSinkDoesNotBreakAStreamAndSinksRunUnderTheCapturedTenantOnAnotherThread() {
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of()));
        Recording recording = new Recording();
        when(streamChain.nextStream(any()))
                .thenReturn(Flux.just(chunk(), chunk()).publishOn(Schedulers.boundedElastic()));
        LlmMetricsAdvisor advisor =
                advisor(mock(LlmCallLogService.class)).withSinks(List.of(new Boom(), recording));

        List<ChatClientResponse> out =
                advisor.adviseStream(request(), streamChain).collectList().block();
        TenantContext.clear();

        // doFinally runs just after the terminal signal reaches the subscriber.
        long deadline = System.currentTimeMillis() + 3000;
        while (recording.seen.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(2, out.size());
        assertEquals(1, recording.seen.size());
        assertEquals("tenant-1", recording.tenant.get());
    }

    @Test
    void cancelledStreamReachesSinksAsAConsumerCancellationNotAnError() {
        Recording recording = new Recording();
        when(streamChain.nextStream(any())).thenReturn(Flux.just(chunk(), chunk(), chunk()));

        advisor(mock(LlmCallLogService.class))
                .withSinks(List.of(recording))
                .adviseStream(request(), streamChain)
                .take(1)
                .blockLast();

        LlmCallObservation seen = recording.seen.get(0);
        assertTrue(seen.streamCancelled());
        assertEquals(false, seen.success());
        assertEquals(false, seen.countAsError());
    }
}
