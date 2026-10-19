package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.ToolClassificationRegistry;
import com.agenticai.core.chat.gateway.LlmGatewayToolOutputRedactor;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicyCallback;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicyEvaluator;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicySet;
import com.agenticai.core.guardrails.toolargs.ToolArgPolicySource;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Flag-off identity for the per-tool argument policies: with no source bean, or a source whose
 * feature flag is off, the guard chain gets back the very same callback object (so today's
 * behaviour is untouched); with the flag on it is wrapped and a violating call never reaches the
 * tool.
 */
class AgentToolGovernanceArgPolicyTest {

    private static final UUID AGENT = UUID.randomUUID();

    private AgentToolGovernance governance;
    private ToolCallback inner;

    @BeforeEach
    void setUp() {
        governance =
                new AgentToolGovernance(
                        providerOf((ToolClassificationRegistry) null),
                        providerOf((MeterRegistry) null),
                        providerOf((LlmGatewayToolOutputRedactor) null),
                        false,
                        "");
        inner = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("fetch_web_page");
        when(inner.getToolDefinition()).thenReturn(definition);
        when(inner.call(anyString())).thenReturn("ran");
    }

    private static final class Source implements ToolArgPolicySource {
        private final boolean enabled;
        private final ToolArgPolicyEvaluator evaluator;

        Source(boolean enabled, ToolArgPolicyEvaluator evaluator) {
            this.enabled = enabled;
            this.evaluator = evaluator;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public ToolArgPolicyEvaluator evaluatorFor(UUID agentId) {
            return evaluator;
        }
    }

    private static ToolArgPolicyEvaluator urlPolicy() {
        return new ToolArgPolicyEvaluator(
                ToolArgPolicySet.parse(
                        "{\"policies\":[{\"tool\":\"fetch_web_page\",\"rules\":[{\"path\":\"url\",\"urlHostIn\":[\"example.com\"]}]}]}"));
    }

    @Test
    void withNoSourceBeanTheCallbackIsReturnedUnchanged() {
        assertSame(inner, governance.withToolArgPolicy(inner, AGENT, "s", new AtomicInteger(), 10));
    }

    @Test
    void withTheFlagOffTheCallbackIsReturnedUnchanged() {
        governance.setToolArgPolicySource(new Source(false, urlPolicy()));
        assertSame(inner, governance.withToolArgPolicy(inner, AGENT, "s", new AtomicInteger(), 10));
    }

    @Test
    void withTheFlagOnTheCallbackIsWrappedAndAViolatingCallNeverReachesTheTool() {
        governance.setToolArgPolicySource(new Source(true, urlPolicy()));

        ToolCallback wrapped =
                governance.withToolArgPolicy(inner, AGENT, "s", new AtomicInteger(), 10);

        assertNotSame(inner, wrapped);
        assertInstanceOf(ToolArgPolicyCallback.class, wrapped);
        assertEquals("ran", wrapped.call("{\"url\":\"https://example.com/\"}"));
        String refused = wrapped.call("{\"url\":\"https://evil.test/\"}");
        assertTrue(refused.contains("was not executed"), refused);
        verify(inner, never()).call("{\"url\":\"https://evil.test/\"}");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
