package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/**
 * {@code app.observability.genai-attrs} = legacy | new | both: the naming class itself, and the two
 * places that emit the renamed provider attribute (the {@code invoke_agent} observation and the
 * chat-span tagging in {@link LlmMetricsAdvisor}). Rename source: OpenTelemetry semantic conventions
 * v1.37.0, {@code gen_ai.system} -> {@code gen_ai.provider.name}
 * (https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/).
 */
class GenAiAttributeNamesTest {

    private static final UUID AGENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------ pure class

    @Test
    void legacyModeEmitsOnlyTheDeprecatedKey() {
        assertEquals(List.of("gen_ai.system"), GenAiAttributeNames.of("legacy").providerKeys());
    }

    @Test
    void newModeEmitsOnlyTheCurrentKey() {
        assertEquals(List.of("gen_ai.provider.name"), GenAiAttributeNames.of("new").providerKeys());
    }

    @Test
    void bothModeEmitsBothKeys() {
        assertEquals(List.of("gen_ai.system", "gen_ai.provider.name"), GenAiAttributeNames.of("both").providerKeys());
    }

    @Test
    void unknownBlankOrMissingValueMeansBoth() {
        for (String raw : new String[] {null, "", "   ", "bogus", "old", "true", "newest"}) {
            assertEquals(GenAiAttributeNames.Mode.BOTH, GenAiAttributeNames.of(raw).mode(), "raw=" + raw);
            assertEquals(2, GenAiAttributeNames.of(raw).providerKeys().size(), "raw=" + raw);
        }
        assertEquals(GenAiAttributeNames.Mode.BOTH, new GenAiAttributeNames(null).mode());
        assertEquals(GenAiAttributeNames.Mode.BOTH, GenAiAttributeNames.DEFAULT.mode());
    }

    @Test
    void parsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(GenAiAttributeNames.Mode.LEGACY, GenAiAttributeNames.Mode.parse(" LEGACY "));
        assertEquals(GenAiAttributeNames.Mode.NEW, GenAiAttributeNames.Mode.parse("New"));
        assertEquals(GenAiAttributeNames.Mode.BOTH, GenAiAttributeNames.Mode.parse("Both"));
    }

    // ------------------------------------------------------------------ invoke_agent observation

    private Map<String, String> invokeAgentKeys(GenAiAttributeNames names) {
        Map<String, String> keys = new HashMap<>();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                    keys.put(kv.getKey(), kv.getValue());
                }
                for (KeyValue kv : context.getHighCardinalityKeyValues()) {
                    keys.put(kv.getKey(), kv.getValue());
                }
            }
        });
        Observation o = AgentInvocationObservation.start(
            registry, "openai", "gpt-x", AGENT, null, null, "sess-1", "tenant-1", "user-1", names);
        assertNotNull(o);
        o.stop();
        return keys;
    }

    @Test
    void invokeAgentSpanLegacyHasOnlyGenAiSystem() {
        Map<String, String> keys = invokeAgentKeys(GenAiAttributeNames.of("legacy"));
        assertEquals("openai", keys.get("gen_ai.system"));
        assertFalse(keys.containsKey("gen_ai.provider.name"));
    }

    @Test
    void invokeAgentSpanNewHasOnlyGenAiProviderName() {
        Map<String, String> keys = invokeAgentKeys(GenAiAttributeNames.of("new"));
        assertEquals("openai", keys.get("gen_ai.provider.name"));
        assertFalse(keys.containsKey("gen_ai.system"));
    }

    @Test
    void invokeAgentSpanBothHasBothWithTheSameValueAndNothingElseChanged() {
        Map<String, String> keys = invokeAgentKeys(GenAiAttributeNames.of("both"));
        assertEquals("openai", keys.get("gen_ai.system"));
        assertEquals("openai", keys.get("gen_ai.provider.name"));
        assertEquals("invoke_agent", keys.get("gen_ai.operation.name"));
        assertEquals("gpt-x", keys.get("gen_ai.request.model"));
        assertEquals(AGENT.toString(), keys.get("gen_ai.agent.id"));
        assertEquals("sess-1", keys.get("gen_ai.conversation.id"));
    }

    @Test
    void invokeAgentOverloadWithoutNamesDefaultsToBoth() {
        ObservationRegistry registry = ObservationRegistry.create();
        Map<String, String> keys = new HashMap<>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                for (KeyValue kv : context.getLowCardinalityKeyValues()) {
                    keys.put(kv.getKey(), kv.getValue());
                }
            }
        });
        Observation o = AgentInvocationObservation.start(registry, "openai", "gpt-x", AGENT, null, null, null, null, null);
        assertNotNull(o);
        o.stop();
        assertTrue(keys.containsKey("gen_ai.system"));
        assertTrue(keys.containsKey("gen_ai.provider.name"));
    }

    // ------------------------------------------------------------------ chat span tagging

    /** Runs one advised call under a recording span and returns every attribute the advisor set. */
    private Map<String, Object> chatSpanAttributes(LlmMetricsAdvisor metrics) {
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
        TenantContext.set(TenantContext.of("tenant-9", "user-42", Set.of()));
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(mock(ChatClientResponse.class));
        Prompt prompt = new Prompt(List.of(new SystemMessage("You are a helpful bot."), new UserMessage("hi")));
        try (Scope ignored = span.makeCurrent()) {
            metrics.adviseCall(new ChatClientRequest(prompt, Map.of(LlmMetricsAdvisor.AGENT_ID_PARAM, AGENT.toString())), chain);
        }
        return attributes;
    }

    private LlmMetricsAdvisor advisor(ProviderName provider) {
        return new LlmMetricsAdvisor(
            new LlmCallMetricsService(), mock(LlmCallLogService.class), provider, "model-x",
            new SimpleMeterRegistry(), new LlmPricingCatalog());
    }

    @Test
    void chatSpanLegacyHasOnlyGenAiSystem() {
        Map<String, Object> a = chatSpanAttributes(advisor(ProviderName.OPENAI).withGenAiAttributeNames(GenAiAttributeNames.of("legacy")));
        assertEquals("openai", a.get("gen_ai.system"));
        assertFalse(a.containsKey("gen_ai.provider.name"));
    }

    @Test
    void chatSpanNewHasOnlyGenAiProviderName() {
        Map<String, Object> a = chatSpanAttributes(advisor(ProviderName.OPENAI).withGenAiAttributeNames(GenAiAttributeNames.of("new")));
        assertEquals("openai", a.get("gen_ai.provider.name"));
        assertFalse(a.containsKey("gen_ai.system"));
    }

    @Test
    void chatSpanBothHasBothAndUnconfiguredAdvisorDefaultsToBoth() {
        Map<String, Object> both = chatSpanAttributes(advisor(ProviderName.OPENAI).withGenAiAttributeNames(GenAiAttributeNames.of("both")));
        assertEquals("openai", both.get("gen_ai.system"));
        assertEquals("openai", both.get("gen_ai.provider.name"));
        Map<String, Object> unconfigured = chatSpanAttributes(advisor(ProviderName.OPENAI));
        assertEquals("openai", unconfigured.get("gen_ai.system"));
        assertEquals("openai", unconfigured.get("gen_ai.provider.name"));
    }

    @Test
    void grokKeepsItsXaiValueUnderBothKeysAndOtherAttributesAreUntouched() {
        Map<String, Object> a = chatSpanAttributes(advisor(ProviderName.GROK).withGenAiAttributeNames(GenAiAttributeNames.of("both")));
        assertEquals("xai", a.get("gen_ai.system"));
        assertEquals("xai", a.get("gen_ai.provider.name"));
        assertEquals("chat", a.get("gen_ai.operation.name"));
        assertEquals("model-x", a.get("gen_ai.request.model"));
        assertEquals(AGENT.toString(), a.get("gen_ai.agent.id"));
    }
}
