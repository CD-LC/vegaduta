package com.agenticai.core.agents.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.guardrails.ToolResultMarker;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What the model reads when a tool's circuit breaker is open is the registry's {@code toolresult.tool_breaker.*}:
 * byte-identical to the text that was hardcoded, and an override of the tenant or the agent applies on the real
 * {@link LoggingToolCallback#call} path.
 */
class LoggingToolCallbackBreakerPromptTest {

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final UUID agent = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private LoggingToolCallback blocked(ToolCircuitBreakerService.OpenInfo open) {
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition())
                .thenReturn(ToolDefinition.builder().name("flaky").description("d").inputSchema("{}").build());
        ToolCircuitBreakerService breaker = mock(ToolCircuitBreakerService.class);
        when(breaker.openInfo(any())).thenReturn(open);
        return new LoggingToolCallback(
                        delegate, mock(ToolCallLogRepository.class), agent, "s1", mock(ObjectProvider.class), breaker, null)
                .withPrompts(resolver);
    }

    /** The text before the marker was added, as the code built it before the migration. */
    private static String oldWording(String tool, String lastError, long minutes) {
        StringBuilder message =
                new StringBuilder("This tool ('").append(tool).append("') has been temporarily disabled after repeated failures");
        if (lastError != null && !lastError.isBlank()) {
            message.append(" (most recent error: ").append(lastError).append(")");
        }
        message.append(". It becomes available again automatically in about ")
                .append(minutes)
                .append(minutes == 1 ? " minute" : " minutes")
                .append(". Try a different approach, or let the user know this capability is briefly unavailable.");
        return message.toString();
    }

    @Test
    void theShippedWordingIsByteIdenticalForEveryShapeOfTheMessage() {
        for (ToolCircuitBreakerService.OpenInfo open :
                new ToolCircuitBreakerService.OpenInfo[] {
                    new ToolCircuitBreakerService.OpenInfo(120, "timeout"),
                    new ToolCircuitBreakerService.OpenInfo(30, null),
                    new ToolCircuitBreakerService.OpenInfo(10, "  "),
                    new ToolCircuitBreakerService.OpenInfo(300, "HTTP 503 {\"detail\":\"x\"}"),
                    new ToolCircuitBreakerService.OpenInfo(0, "boom")
                }) {
            long minutes = Math.max(1, Math.round(open.secondsRemaining() / 60.0));
            String expected = ToolResultMarker.mark(oldWording("flaky", open.lastError(), minutes), ToolResultMarker.Kind.BLOCKED);
            assertThat(blocked(open).call("{}")).isEqualTo(expected);
        }
        assertThat(PromptResolver.defaultsOnly().catalog().problems()).isEmpty();
    }

    @Test
    void aTenantOverrideAndAnAgentOverrideApply() {
        TenantContext.set(TenantContext.of("t1", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", "toolresult.tool_breaker.disabled", "en", "{tool} is paused{last_error} for {wait}.", true, 1, "owner", Instant.now()));
        store.put(new PromptOverride(PromptScope.TENANT, "", "toolresult.tool_breaker.last_error", "en", " because: {error}", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        ToolCircuitBreakerService.OpenInfo open = new ToolCircuitBreakerService.OpenInfo(120, "timeout");
        assertThat(blocked(open).call("{}")).contains("flaky is paused because: timeout for 2 minutes.");

        store.put(new PromptOverride(PromptScope.AGENT, agent.toString(), "toolresult.tool_breaker.disabled", "en", "Agent: {tool} is down.", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        assertThat(blocked(open).call("{}")).contains("Agent: flaky is down.");
    }
}
