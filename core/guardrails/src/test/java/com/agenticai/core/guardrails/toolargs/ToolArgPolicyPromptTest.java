package com.agenticai.core.guardrails.toolargs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The refusal {@link ToolArgPolicyCallback} hands the model for a call whose arguments break the agent's argument
 * policy is the prompt {@code toolresult.guard.arg_policy_violation}: byte for byte the old sentence by default, the
 * agent's or the tenant's override when there is one.
 */
class ToolArgPolicyPromptTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String TOOL = "fetch_web_page";
    private static final String BAD = "{\"url\":\"https://evil.test/x\"}";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ToolArgPolicyCallback callback() {
        ToolCallback delegate = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(TOOL);
        when(delegate.getToolDefinition()).thenReturn(definition);
        when(delegate.call(anyString())).thenReturn("fetched");
        ToolArgPolicySource source = new ToolArgPolicySource() {
            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public ToolArgPolicyEvaluator evaluatorFor(UUID agentId) {
                return new ToolArgPolicyEvaluator(ToolArgPolicySet.parse(
                        "{\"policies\":[{\"tool\":\"" + TOOL + "\",\"rules\":[{\"path\":\"url\",\"urlHostIn\":[\"example.com\"]}]}]}"));
            }

            @Override
            public void onViolation(UUID agentId, String sessionId, String toolName, String reason) {}
        };
        return new ToolArgPolicyCallback(delegate, source, AGENT, "s1", new AtomicInteger(), 10).withPrompts(resolver);
    }

    @Test
    void theRefusalIsTheOldSentenceByDefaultAndFollowsAnAgentOverride() {
        TenantContext.set(TenantContext.of("t1", "owner", Set.of()));

        String byDefault = callback().call(BAD);

        assertThat(byDefault)
                .startsWith("This tool call ('" + TOOL + "') was not executed: its arguments broke this agent's argument policy for the tool - ")
                .endsWith(". Fix the arguments and try again, or proceed without this tool.");
        String reason = byDefault.substring(
                ("This tool call ('" + TOOL + "') was not executed: its arguments broke this agent's argument policy for the tool - ").length(),
                byDefault.length() - ". Fix the arguments and try again, or proceed without this tool.".length());

        store.put(new PromptOverride(
                PromptScope.AGENT, AGENT.toString(), "toolresult.guard.arg_policy_violation", "en",
                "Refused {tool}: {reason}", true, 1, "owner", Instant.now()));
        resolver.evictAll();

        assertThat(callback().call(BAD)).isEqualTo("Refused " + TOOL + ": " + reason);
    }
}
