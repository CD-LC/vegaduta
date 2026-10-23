package com.agenticai.core.common.studioapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.workflow.WorkflowService;
import com.agenticai.core.workflow.share.WorkflowShareService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/** The default persona of a studio app's AI is the registry key {@code studioapp.default_system}; the app's own prompt wins. */
class StudioAppDefaultSystemPromptTest {

    private static final String GOLDEN =
            "You are the AI built into a small app. Answer the user's request directly and"
                    + " concisely, plain text, no markdown fences.";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String systemSentFor(String appPrompt, boolean withResolver) {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(any(String.class)).user(any(String.class)).call().content()).thenReturn("hi");
        FreeTierChatClientService freeTier = mock(FreeTierChatClientService.class);
        when(freeTier.buildClientChainForFreeTenant(anyString(), any(), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(List.of(client));
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan("acme")).thenReturn(TenantPlan.FREE);
        StudioAppInvokeService service =
                new StudioAppInvokeService(
                        null, mock(AgentChatService.class), mock(WorkflowShareService.class), mock(WorkflowService.class), freeTier, limits);
        if (withResolver) {
            service.setPromptResolver(resolver);
        }
        StudioApp app = mock(StudioApp.class);
        when(app.getAiSystemPrompt()).thenReturn(appPrompt);
        when(app.getAiMaxTokens()).thenReturn(512);
        service.aiChat(app, "hello", null);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), org.mockito.Mockito.atLeastOnce()).system(system.capture());
        return system.getValue();
    }

    @Test
    void theShippedPersonaIsByteIdenticalAndTheAppsOwnPromptStillWins() {
        assertThat(PromptResolver.defaultsOnly().text(StudioAppInvokeService.DEFAULT_AI_SYSTEM_KEY)).isEqualTo(GOLDEN);
        assertThat(systemSentFor(null, false)).isEqualTo(GOLDEN);
        assertThat(systemSentFor("  ", false)).isEqualTo(GOLDEN);
        assertThat(systemSentFor("You are a pirate.", true)).isEqualTo("You are a pirate.");
    }

    @Test
    void aTenantOverrideReachesTheRealCallPath() {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", StudioAppInvokeService.DEFAULT_AI_SYSTEM_KEY, "en", "Be terse.", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        assertThat(systemSentFor(null, true)).isEqualTo("Be terse.");
    }
}
