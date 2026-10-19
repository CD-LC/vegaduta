package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

/**
 * OTel GenAI spans: {@code invoke_agent} wrapping the model call with the
 * {@code chat}/{@code execute_tool} spans as children, content kept off, agent
 * and prompt version on the trace, and the Langfuse user being the real user.
 */
class GenAiSpansTest {

    private static final UUID AGENT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** Records every started observation's name, contextual name, key values and parent. */
    private static final class Recorder implements ObservationHandler<Observation.Context> {
        final List<Observation.Context> started = new ArrayList<>();
        final List<Observation.Context> errored = new ArrayList<>();
        final List<Observation.Context> stopped = new ArrayList<>();

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStart(Observation.Context context) {
            started.add(context);
        }

        @Override
        public void onError(Observation.Context context) {
            errored.add(context);
        }

        @Override
        public void onStop(Observation.Context context) {
            stopped.add(context);
        }
    }

    private static Map<String, String> keyValues(Observation.Context context) {
        Map<String, String> all = new HashMap<>();
        for (KeyValue kv : context.getLowCardinalityKeyValues()) {
            all.put(kv.getKey(), kv.getValue());
        }
        for (KeyValue kv : context.getHighCardinalityKeyValues()) {
            all.put(kv.getKey(), kv.getValue());
        }
        return all;
    }

    private LlmMetricsAdvisor advisor(ObservationRegistry registry) {
        return new LlmMetricsAdvisor(
            new LlmCallMetricsService(), mock(LlmCallLogService.class), ProviderName.OPENAI, "gpt-x",
            new SimpleMeterRegistry(), new LlmPricingCatalog()).withObservationRegistry(registry);
    }

    private static ChatClientRequest agentRequest(String system) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage("secret user question")));
        return new ChatClientRequest(prompt, Map.of(
            LlmMetricsAdvisor.AGENT_ID_PARAM, AGENT.toString(),
            LlmMetricsAdvisor.AGENT_VERSION_ID_PARAM, VERSION.toString(),
            LlmMetricsAdvisor.SESSION_ID_PARAM, "sess-1"));
    }

    // ------------------------------------------------------------------ invoke_agent

    @Test
    void invokeAgentSpanWrapsTheModelCallAndTheChatSpanIsItsChild() {
        Recorder recorder = new Recorder();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorder);
        TenantContext.set(TenantContext.of("tenant-9", "user-42", Set.of()));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> {
            // what Spring AI's model does inside the advisor chain
            Observation.createNotStarted("chat", registry).observe(() -> { });
            return mock(ChatClientResponse.class);
        });

        advisor(registry).adviseCall(agentRequest("You are a helpful bot."), chain);

        Observation.Context invoke = recorder.started.stream()
            .filter(c -> "invoke_agent".equals(c.getName())).findFirst().orElseThrow();
        Observation.Context chat = recorder.started.stream()
            .filter(c -> "chat".equals(c.getName())).findFirst().orElseThrow();
        assertNotNull(chat.getParentObservation(), "chat span must be parented");
        assertEquals(invoke, chat.getParentObservation().getContextView(),
            "chat is a child of invoke_agent");
        assertTrue(recorder.stopped.contains(invoke), "span is ended");
        assertTrue(invoke.getContextualName().startsWith("invoke_agent "));

        Map<String, String> attrs = keyValues(invoke);
        assertEquals("invoke_agent", attrs.get("gen_ai.operation.name"));
        assertEquals("gpt-x", attrs.get("gen_ai.request.model"));
        assertEquals(AGENT.toString(), attrs.get("gen_ai.agent.id"));
        assertEquals(VERSION.toString(), attrs.get("gen_ai.agent.version"));
        assertEquals("sess-1", attrs.get("gen_ai.conversation.id"));
        assertEquals("tenant-9", attrs.get("vegaduta.tenant.id"));
        assertEquals("user-42", attrs.get("langfuse.user.id"), "real user, not the tenant");
        assertEquals(12, attrs.get("vegaduta.prompt.version").length());
        // agent id / version / prompt hash must not become meter tags
        assertFalse(invoke.getLowCardinalityKeyValues().stream().anyMatch(kv -> kv.getKey().equals("gen_ai.agent.id")));
    }

    @Test
    void contentIsNeverOnTheSpan() {
        Recorder recorder = new Recorder();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorder);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        advisor(registry).adviseCall(agentRequest("TOP-SECRET-SYSTEM-PROMPT"), chain);

        for (Observation.Context context : recorder.started) {
            for (String value : keyValues(context).values()) {
                assertFalse(value.contains("TOP-SECRET-SYSTEM-PROMPT"), "system prompt leaked: " + value);
                assertFalse(value.contains("secret user question"), "user prompt leaked: " + value);
            }
        }
    }

    @Test
    void failedCallMarksTheSpanErroredAndStillEndsIt() {
        Recorder recorder = new Recorder();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorder);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenThrow(new IllegalStateException("boom"));

        try {
            advisor(registry).adviseCall(agentRequest("sys"), chain);
        } catch (IllegalStateException expected) {
            // rethrown unchanged
        }

        assertEquals(1, recorder.errored.size());
        assertEquals("invoke_agent", recorder.errored.get(0).getName());
        assertEquals(1, recorder.stopped.size());
    }

    @Test
    void noSpanWithoutAnAgentOrWithANoopRegistry() {
        Recorder recorder = new Recorder();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorder);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        advisor(registry).adviseCall(new ChatClientRequest(new Prompt("hi"), Map.of()), chain);
        advisor(ObservationRegistry.NOOP).adviseCall(agentRequest("sys"), chain);

        assertTrue(recorder.started.isEmpty());
    }

    @Test
    void promptVersionIsStableForSameSystemPromptAndChangesWithIt() {
        String a = AgentInvocationObservation.promptVersionOf(new Prompt(new SystemMessage("alpha")));
        String a2 = AgentInvocationObservation.promptVersionOf(new Prompt(List.of(new SystemMessage("alpha"), new UserMessage("x"))));
        String b = AgentInvocationObservation.promptVersionOf(new Prompt(new SystemMessage("beta")));
        assertEquals(a, a2, "user text must not affect the version");
        assertTrue(!a.equals(b));
        assertNull(AgentInvocationObservation.promptVersionOf(new Prompt("no system message")));
    }

    // ------------------------------------------------------------------ execute_tool

    @Test
    void toolConventionUsesGenAiNamingAndDropsDefinitionContent() {
        GenAiToolObservationConvention convention = new GenAiToolObservationConvention();
        ToolCallingObservationContext context = ToolCallingObservationContext.builder()
            .toolDefinition(DefaultToolDefinition.builder()
                .name("search_docs").description("PRIVATE tenant-authored description")
                .inputSchema("{\"secret\":\"schema\"}").build())
            .toolCallArguments("{\"q\":\"customer ssn\"}")
            .build();

        assertEquals("execute_tool", convention.getName());
        assertEquals("execute_tool search_docs", convention.getContextualName(context));
        Map<String, String> low = new HashMap<>();
        convention.getLowCardinalityKeyValues(context).forEach(kv -> low.put(kv.getKey(), kv.getValue()));
        assertEquals("execute_tool", low.get("gen_ai.operation.name"));
        assertEquals("search_docs", low.get("gen_ai.tool.name"));
        assertEquals("function", low.get("gen_ai.tool.type"));
        assertEquals(0, convention.getHighCardinalityKeyValues(context).stream().count(),
            "no tool description / schema / arguments on the span");
    }

    @Test
    void realToolManagerEmitsAnExecuteToolObservationUnderTheCurrentSpan() throws Exception {
        Recorder recorder = new Recorder();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(recorder);
        org.springframework.ai.model.tool.DefaultToolCallingManager manager =
            org.springframework.ai.model.tool.ToolCallingManager.builder().observationRegistry(registry).build();
        manager.setObservationConvention(new GenAiToolObservationConvention());

        org.springframework.ai.tool.ToolCallback tool = new org.springframework.ai.tool.ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("lookup").description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "\"ok\"";
            }
        };
        org.springframework.ai.model.tool.ToolCallingChatOptions options =
            org.springframework.ai.model.tool.ToolCallingChatOptions.builder().toolCallbacks(tool).build();
        Prompt prompt = new Prompt(List.of(new UserMessage("go")), options);
        org.springframework.ai.chat.messages.AssistantMessage assistant =
            org.springframework.ai.chat.messages.AssistantMessage.builder().content("")
                .toolCalls(List.of(new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("c1", "function", "lookup", "{}")))
                .build();
        org.springframework.ai.chat.model.ChatResponse response = new org.springframework.ai.chat.model.ChatResponse(
            List.of(new org.springframework.ai.chat.model.Generation(assistant)));

        Observation parent = Observation.createNotStarted("invoke_agent", registry).start();
        try (Observation.Scope ignored = parent.openScope()) {
            manager.executeToolCalls(prompt, response);
        } finally {
            parent.stop();
        }

        Observation.Context tool0 = recorder.started.stream()
            .filter(c -> "execute_tool".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("execute_tool lookup", tool0.getContextualName());
        assertEquals("invoke_agent", tool0.getParentObservation().getContextView().getName());
    }

    // ------------------------------------------------------------------ trace attributes

    @Test
    void traceCarriesRealUserSeparateTenantAgentVersionPromptVersionAndReasoning() {
        Map<String, Object> attributes = new HashMap<>();
        Span span = Mockito.mock(Span.class, inv -> {
            String method = inv.getMethod().getName();
            if (method.equals("setAttribute") && inv.getArguments()[0] instanceof String key) {
                attributes.put(key, inv.getArguments()[1]);
                return inv.getMock();
            }
            if (method.equals("getSpanContext")) {
                return SpanContext.create(
                    "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());
            }
            if (method.equals("storeInContext") || method.equals("makeCurrent")) {
                return inv.callRealMethod();
            }
            return inv.getMethod().getReturnType().isInstance(inv.getMock()) ? inv.getMock() : null;
        });
        LlmCallLogService logService = mock(LlmCallLogService.class);
        LlmMetricsAdvisor metrics = new LlmMetricsAdvisor(
            new LlmCallMetricsService(), logService, ProviderName.GEMINI, "gemini-x",
            new SimpleMeterRegistry(), new LlmPricingCatalog());
        TenantContext.set(TenantContext.of("tenant-9", "user-42", Set.of()));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        try (Scope ignored = span.makeCurrent()) {
            metrics.adviseCall(agentRequest("You are a helpful bot."), chain);
        }

        assertEquals("user-42", attributes.get("langfuse.user.id"), "real user is the Langfuse user");
        assertEquals("tenant-9", attributes.get("vegaduta.tenant.id"), "tenant kept as its own attribute");
        assertEquals("tenant-9", attributes.get("langfuse.trace.metadata.tenantId"));
        assertEquals(VERSION.toString(), attributes.get("gen_ai.agent.version"));
        assertEquals(VERSION.toString(), attributes.get("langfuse.trace.metadata.agentVersionId"));
        assertNotNull(attributes.get("vegaduta.prompt.version"));
        assertEquals(attributes.get("vegaduta.prompt.version"), attributes.get("langfuse.trace.metadata.promptVersion"));
        assertEquals(AGENT.toString(), attributes.get("gen_ai.agent.id"));
    }

    @Test
    void langfuseUserFallsBackToTenantOnlyWhenNoSubject() {
        Map<String, Object> attributes = new HashMap<>();
        Span span = Mockito.mock(Span.class, inv -> {
            String method = inv.getMethod().getName();
            if (method.equals("setAttribute") && inv.getArguments()[0] instanceof String key) {
                attributes.put(key, inv.getArguments()[1]);
                return inv.getMock();
            }
            if (method.equals("getSpanContext")) {
                return SpanContext.create(
                    "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());
            }
            if (method.equals("storeInContext") || method.equals("makeCurrent")) {
                return inv.callRealMethod();
            }
            return inv.getMethod().getReturnType().isInstance(inv.getMock()) ? inv.getMock() : null;
        });
        LlmMetricsAdvisor metrics = new LlmMetricsAdvisor(
            new LlmCallMetricsService(), mock(LlmCallLogService.class), ProviderName.OPENAI, "gpt-x",
            new SimpleMeterRegistry(), new LlmPricingCatalog());
        TenantContext.set(TenantContext.of("tenant-9", null, Set.of()));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

        try (Scope ignored = span.makeCurrent()) {
            metrics.adviseCall(agentRequest("sys"), chain);
        }

        assertEquals("tenant-9", attributes.get("langfuse.user.id"));
    }

    @Test
    void userHashModeSendsASaltedHashNeverTheRawUserAndTenantModeSendsTheTenant() {
        for (String mode : new String[] {"user-hash", "tenant"}) {
            Map<String, Object> attributes = new HashMap<>();
            Span span = Mockito.mock(Span.class, inv -> {
                String method = inv.getMethod().getName();
                if (method.equals("setAttribute") && inv.getArguments()[0] instanceof String key) {
                    attributes.put(key, inv.getArguments()[1]);
                    return inv.getMock();
                }
                if (method.equals("getSpanContext")) {
                    return SpanContext.create(
                        "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", TraceFlags.getSampled(), TraceState.getDefault());
                }
                if (method.equals("storeInContext") || method.equals("makeCurrent")) {
                    return inv.callRealMethod();
                }
                return inv.getMethod().getReturnType().isInstance(inv.getMock()) ? inv.getMock() : null;
            });
            LlmMetricsAdvisor metrics = new LlmMetricsAdvisor(
                new LlmCallMetricsService(), mock(LlmCallLogService.class), ProviderName.OPENAI, "gpt-x",
                new SimpleMeterRegistry(), new LlmPricingCatalog())
                .withLangfuseUserId(LangfuseUserId.of(mode, "pepper"));
            TenantContext.set(TenantContext.of("tenant-9", "user-42", Set.of()));
            CallAdvisorChain chain = mock(CallAdvisorChain.class);
            when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));

            try (Scope ignored = span.makeCurrent()) {
                metrics.adviseCall(agentRequest("sys"), chain);
            }

            Object sent = attributes.get("langfuse.user.id");
            if (mode.equals("tenant")) {
                assertEquals("tenant-9", sent);
            } else {
                assertNotNull(sent);
                assertFalse(String.valueOf(sent).contains("user-42"), "raw user id must not be sent");
                assertEquals(LangfuseUserId.of("user-hash", "pepper").resolve("tenant-9", "user-42"), sent);
            }
            assertEquals("tenant-9", attributes.get("vegaduta.tenant.id"), "tenant stays its own attribute");
            TenantContext.clear();
        }
    }
}
