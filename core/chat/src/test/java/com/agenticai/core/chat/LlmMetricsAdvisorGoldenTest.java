package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Golden characterisation of everything {@link LlmMetricsAdvisor} emits for one call: the chat-span
 * attributes, every meter (name, tags, kind, count/total) and every argument handed to {@link
 * LlmCallLogService#record}. Written against the pre-split advisor and kept unchanged across the
 * observer split, so span names/attributes and metric names/tags stay byte-identical.
 */
class LlmMetricsAdvisorGoldenTest {

    private static final UUID AGENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private final TreeMap<String, Object> spanAttributes = new TreeMap<>();
    private final List<String> recorded = new ArrayList<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private Span recordingSpan() {
        return Mockito.mock(
                Span.class,
                inv -> {
                    String method = inv.getMethod().getName();
                    if (method.equals("setAttribute") && inv.getArguments().length == 2) {
                        Object key = inv.getArguments()[0];
                        String name =
                                key instanceof String s
                                        ? s
                                        : ((io.opentelemetry.api.common.AttributeKey<?>) key)
                                                .getKey();
                        spanAttributes.put(name, String.valueOf(inv.getArguments()[1]));
                        return inv.getMock();
                    }
                    if (method.equals("getSpanContext")) {
                        return SpanContext.create(
                                "0af7651916cd43dd8448eb211c80319c",
                                "b7ad6b7169203331",
                                TraceFlags.getSampled(),
                                TraceState.getDefault());
                    }
                    if (method.equals("storeInContext") || method.equals("makeCurrent")) {
                        return inv.callRealMethod();
                    }
                    return inv.getMethod().getReturnType().isInstance(inv.getMock())
                            ? inv.getMock()
                            : null;
                });
    }

    private LlmCallLogService logService() {
        LlmCallLogService service = mock(LlmCallLogService.class);
        Mockito.doAnswer(
                        inv -> {
                            Object[] a = inv.getArguments();
                            // 0 agentId 1 sessionId 2 provider 3 model 4 prompt 5 completion
                            // 6 total 7 success 8 errorMessage 9 requestId(skip) 10 exceptionType
                            // 11 stackTrace(first line only) 12 latency(skip) 13 startedAt(skip)
                            // 14 experiment 15 traceId 16 spanId 17 cacheCreate 18 cacheRead
                            // 19 agentVersionId 20 billableReasoning
                            String stack = a[11] == null ? "null" : "present";
                            List<Object> parts = new ArrayList<>();
                            for (int i :
                                    new int[] {
                                        0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 14, 15, 16, 17, 18, 19, 20
                                    }) {
                                parts.add(a[i]);
                            }
                            recorded.add(parts + " stack=" + stack);
                            return null;
                        })
                .when(service)
                .record(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        Mockito.anyBoolean(),
                        any(),
                        any(),
                        any(),
                        any(),
                        Mockito.anyLong(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any());
        return service;
    }

    private static Usage anthropicUsage(int in, int out, Integer write, Integer read) {
        return SdkUsageFixtures.anthropic(in, out, write, read);
    }

    private static ChatClientResponse responseWith(Usage usage) {
        ChatResponse chatResponse =
                new ChatResponse(
                        List.of(new Generation(new AssistantMessage("ok"))),
                        ChatResponseMetadata.builder().usage(usage).build());
        return new ChatClientResponse(chatResponse, Map.of());
    }

    private LlmMetricsAdvisor advisor() {
        return new LlmMetricsAdvisor(
                new LlmCallMetricsService(),
                logService(),
                ProviderName.ANTHROPIC,
                "claude-sonnet-4-5",
                registry,
                new LlmPricingCatalog());
    }

    private ChatClientRequest request() {
        Prompt prompt =
                new Prompt(
                        List.of(
                                new SystemMessage("You are a helpful bot."),
                                new UserMessage("hi")));
        return new ChatClientRequest(
                prompt,
                Map.of(
                        LlmMetricsAdvisor.AGENT_ID_PARAM, AGENT.toString(),
                        LlmMetricsAdvisor.AGENT_VERSION_ID_PARAM, VERSION.toString(),
                        LlmMetricsAdvisor.SESSION_ID_PARAM, "sess-1",
                        LlmMetricsAdvisor.PROMPT_VERSION_PARAM, "pv-7",
                        LlmMetricsAdvisor.EXPERIMENT_VARIANTS_PARAM, "flag=b"));
    }

    private String meterSnapshot() {
        return registry.getMeters().stream()
                .map(
                        (Meter m) -> {
                            String tags =
                                    m.getId().getTags().stream()
                                            .map(t -> t.getKey() + "=" + t.getValue())
                                            .sorted()
                                            .collect(Collectors.joining(","));
                            String value;
                            if (m instanceof Counter c) {
                                value = "counter " + c.count();
                            } else if (m instanceof Timer t) {
                                value = "timer count=" + t.count();
                            } else if (m instanceof DistributionSummary d) {
                                value = "summary count=" + d.count() + " total=" + d.totalAmount();
                            } else {
                                value = m.getClass().getSimpleName();
                            }
                            return m.getId().getName() + "{" + tags + "} " + value;
                        })
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    private String snapshot() {
        return "SPAN\n"
                + spanAttributes.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("\n"))
                + "\nLOG\n"
                + String.join("\n", recorded)
                + "\nMETERS\n"
                + meterSnapshot();
    }

    private String normalizedSnapshot() {
        return snapshot().replaceAll("after \\d+ ms", "after N ms");
    }

    @Test
    void successfulCallWithCacheUsage() {
        TenantContext.set(TenantContext.of("tenant-9", "user-42", Set.of()));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(responseWith(anthropicUsage(200, 120, 4096, 1800)));
        Span span = recordingSpan();
        try (Scope ignored = span.makeCurrent()) {
            advisor().adviseCall(request(), chain);
        }
        assertEquals(GOLDEN_SUCCESS, normalizedSnapshot());
    }

    @Test
    void failedCall() {
        TenantContext.set(TenantContext.of("tenant-9", "user-42", Set.of()));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenThrow(new IllegalStateException("boom"));
        Span span = recordingSpan();
        try (Scope ignored = span.makeCurrent()) {
            try {
                advisor().adviseCall(request(), chain);
            } catch (IllegalStateException expected) {
                // rethrown unchanged
            }
        }
        assertEquals(GOLDEN_FAILURE, normalizedSnapshot());
    }

    @Test
    void cancelledStream() {
        TenantContext.set(TenantContext.of("tenant-9", "user-42", Set.of()));
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any()))
                .thenReturn(
                        Flux.just(
                                responseWith(new DefaultUsage(800, 0)),
                                responseWith(new DefaultUsage(800, 25)),
                                responseWith(new DefaultUsage(800, 400))));
        Span span = recordingSpan();
        try (Scope ignored = span.makeCurrent()) {
            advisor().adviseStream(request(), chain).take(2).blockLast();
        }
        assertEquals(GOLDEN_CANCEL, normalizedSnapshot());
    }

    private static final String GOLDEN_SUCCESS =
            "SPAN\n"
                + "gen_ai.agent.id=11111111-1111-1111-1111-111111111111\n"
                + "gen_ai.agent.version=22222222-2222-2222-2222-222222222222\n"
                + "gen_ai.conversation.id=sess-1\n"
                + "gen_ai.operation.name=chat\n"
                + "gen_ai.provider.name=anthropic\n"
                + "gen_ai.request.model=claude-sonnet-4-5\n"
                + "gen_ai.system=anthropic\n"
                + "gen_ai.usage.input_tokens=200\n"
                + "gen_ai.usage.output_tokens=120\n"
                + "langfuse.session.id=sess-1\n"
                + "langfuse.trace.metadata.agentId=11111111-1111-1111-1111-111111111111\n"
                + "langfuse.trace.metadata.agentVersionId=22222222-2222-2222-2222-222222222222\n"
                + "langfuse.trace.metadata.cacheHitRatio=0.2952755905511811\n"
                + "langfuse.trace.metadata.cacheReadInputTokens=1800\n"
                + "langfuse.trace.metadata.cacheWriteInputTokens=4096\n"
                + "langfuse.trace.metadata.estimatedCostUsd=0.018300\n"
                + "langfuse.trace.metadata.model=claude-sonnet-4-5\n"
                + "langfuse.trace.metadata.promptVersion=pv-7\n"
                + "langfuse.trace.metadata.tenantId=tenant-9\n"
                + "langfuse.trace.metadata.uncachedInputTokens=200\n"
                + "langfuse.trace.tags=[provider:ANTHROPIC]\n"
                + "langfuse.user.id=user-42\n"
                + "vegaduta.prompt.version=pv-7\n"
                + "vegaduta.tenant.id=tenant-9\n"
                + "LOG\n"
                + "[11111111-1111-1111-1111-111111111111, sess-1, ANTHROPIC, claude-sonnet-4-5,"
                + " 200, 120, 320, true, null, null, flag=b, 0af7651916cd43dd8448eb211c80319c,"
                + " b7ad6b7169203331, 4096, 1800, 22222222-2222-2222-2222-222222222222, null]"
                + " stack=null\n"
                + "METERS\n"
                + "llm.cache.accounted.calls{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9}"
                + " counter 1.0\n"
                + "llm.cache.hit.ratio{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9}"
                + " summary count=1 total=0.2952755905511811\n"
                + "llm.calls{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9} counter"
                + " 1.0\n"
                + "llm.cost.usd{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9} counter"
                + " 0.0183\n"
                + "llm.input.tokens.cache.read{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9}"
                + " counter 1800.0\n"
                + "llm.input.tokens.cache.write{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9}"
                + " counter 4096.0\n"
                + "llm.input.tokens.uncached{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9}"
                + " counter 200.0\n"
                + "llm.latency{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9} timer"
                + " count=1\n"
                + "llm.prompt.prefix.tokens{model=claude-sonnet-4-5,provider=ANTHROPIC} summary"
                + " count=1 total=5.0";
    private static final String GOLDEN_FAILURE =
            "SPAN\n"
                + "error.type=java.lang.IllegalStateException\n"
                + "gen_ai.agent.id=11111111-1111-1111-1111-111111111111\n"
                + "gen_ai.agent.version=22222222-2222-2222-2222-222222222222\n"
                + "gen_ai.conversation.id=sess-1\n"
                + "gen_ai.operation.name=chat\n"
                + "gen_ai.provider.name=anthropic\n"
                + "gen_ai.request.model=claude-sonnet-4-5\n"
                + "gen_ai.system=anthropic\n"
                + "langfuse.session.id=sess-1\n"
                + "langfuse.trace.metadata.agentId=11111111-1111-1111-1111-111111111111\n"
                + "langfuse.trace.metadata.agentVersionId=22222222-2222-2222-2222-222222222222\n"
                + "langfuse.trace.metadata.model=claude-sonnet-4-5\n"
                + "langfuse.trace.metadata.promptVersion=pv-7\n"
                + "langfuse.trace.metadata.tenantId=tenant-9\n"
                + "langfuse.trace.tags=[provider:ANTHROPIC]\n"
                + "langfuse.user.id=user-42\n"
                + "vegaduta.prompt.version=pv-7\n"
                + "vegaduta.tenant.id=tenant-9\n"
                + "LOG\n"
                + "[11111111-1111-1111-1111-111111111111, sess-1, ANTHROPIC, claude-sonnet-4-5,"
                + " null, null, null, false, boom, java.lang.IllegalStateException, flag=b,"
                + " 0af7651916cd43dd8448eb211c80319c, b7ad6b7169203331, null, null,"
                + " 22222222-2222-2222-2222-222222222222, null] stack=present\n"
                + "METERS\n"
                + "llm.call.errors{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9}"
                + " counter 1.0\n"
                + "llm.calls{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9} counter"
                + " 1.0\n"
                + "llm.latency{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9} timer"
                + " count=1\n"
                + "llm.prompt.prefix.tokens{model=claude-sonnet-4-5,provider=ANTHROPIC} summary"
                + " count=1 total=5.0";
    private static final String GOLDEN_CANCEL =
            "SPAN\n"
                + "error.type=java.util.concurrent.CancellationException\n"
                + "gen_ai.agent.id=11111111-1111-1111-1111-111111111111\n"
                + "gen_ai.agent.version=22222222-2222-2222-2222-222222222222\n"
                + "gen_ai.conversation.id=sess-1\n"
                + "gen_ai.operation.name=chat\n"
                + "gen_ai.provider.name=anthropic\n"
                + "gen_ai.request.model=claude-sonnet-4-5\n"
                + "gen_ai.system=anthropic\n"
                + "gen_ai.usage.input_tokens=800\n"
                + "gen_ai.usage.output_tokens=25\n"
                + "langfuse.session.id=sess-1\n"
                + "langfuse.trace.metadata.agentId=11111111-1111-1111-1111-111111111111\n"
                + "langfuse.trace.metadata.agentVersionId=22222222-2222-2222-2222-222222222222\n"
                + "langfuse.trace.metadata.estimatedCostUsd=0.002775\n"
                + "langfuse.trace.metadata.model=claude-sonnet-4-5\n"
                + "langfuse.trace.metadata.promptVersion=pv-7\n"
                + "langfuse.trace.metadata.tenantId=tenant-9\n"
                + "langfuse.trace.tags=[provider:ANTHROPIC]\n"
                + "langfuse.user.id=user-42\n"
                + "vegaduta.prompt.version=pv-7\n"
                + "vegaduta.tenant.id=tenant-9\n"
                + "LOG\n"
                + "[11111111-1111-1111-1111-111111111111, sess-1, ANTHROPIC, claude-sonnet-4-5,"
                + " 800, 25, 825, false, CANCELLED: stream cancelled by the consumer after N ms"
                + " (client disconnect or stream-idle timeout); token counts are the usage reported"
                + " before cancellation and may undercount output,"
                + " java.util.concurrent.CancellationException, flag=b,"
                + " 0af7651916cd43dd8448eb211c80319c, b7ad6b7169203331, null, null,"
                + " 22222222-2222-2222-2222-222222222222, null] stack=present\n"
                + "METERS\n"
                + "llm.calls{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9} counter"
                + " 1.0\n"
                + "llm.cost.usd{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9} counter"
                + " 0.002775\n"
                + "llm.latency{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9} timer"
                + " count=1\n"
                + "llm.prompt.prefix.tokens{model=claude-sonnet-4-5,provider=ANTHROPIC} summary"
                + " count=1 total=5.0\n"
                + "llm.stream.cancelled{model=claude-sonnet-4-5,provider=ANTHROPIC,tenant=tenant-9}"
                + " counter 1.0";
}
