package com.agenticai.core.agents.assist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.assist.dto.AssistRequest;
import com.agenticai.core.agents.auxiliary.AuxiliaryModelResolver;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/** The "Ask AI" helper's system prompt is the registry key {@code edge.ask_ai.system}. */
class AssistServicePromptTest {

    private static final String GOLDEN =
            "You help an admin of an AI agent SaaS platform write clear,\n"
                    + "effective text for one specific form field. You will be told the\n"
                    + "field's purpose, the admin's rough instruction, and (maybe) what\n"
                    + "they've already typed. Reply with ONLY the finished text for that\n"
                    + "field - no preamble, no markdown code fences, no explanation, no\n"
                    + "quotation marks around it. If a current draft is given, improve or\n"
                    + "extend it rather than ignoring it. Keep length appropriate to the\n"
                    + "field (a system prompt can be a paragraph or two; a short\n"
                    + "description field should be one or two sentences).\n";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String systemSent(boolean withResolver) {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(any(String.class)).user(any(String.class)).call().content()).thenReturn(" drafted ");
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan("acme")).thenReturn(TenantPlan.FREE);
        when(limits.usesPlatformPool("acme", TenantPlan.FREE)).thenReturn(true);
        FreeTierChatClientService freeTier = mock(FreeTierChatClientService.class);
        when(freeTier.buildClientForFreeTenant(anyString(), any(TenantPlan.class), anyInt(), anyDouble(), anyDouble()))
                .thenReturn(client);
        AssistService service =
                new AssistService(mock(AuxiliaryModelResolver.class), mock(ChatClientRegistry.class), mock(LlmGatewayService.class), limits, freeTier, 4096);
        if (withResolver) {
            service.setPromptResolver(resolver);
        }
        assertThat(service.draft(new AssistRequest("Description", "make it nice", null)).suggestion()).isEqualTo("drafted");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).system(system.capture());
        return system.getValue();
    }

    @Test
    void theShippedPromptIsByteIdenticalToTheTextBlockItReplaced() {
        assertThat(PromptResolver.defaultsOnly().text(AssistService.FIELD_DRAFT_KEY)).isEqualTo(GOLDEN);
        assertThat(systemSent(false)).isEqualTo(GOLDEN);
    }

    @Test
    void aTenantOverrideReachesTheRealDraftPath() {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", AssistService.FIELD_DRAFT_KEY, "en", "Write the field.", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        assertThat(systemSent(true)).isEqualTo("Write the field.");
    }
}
