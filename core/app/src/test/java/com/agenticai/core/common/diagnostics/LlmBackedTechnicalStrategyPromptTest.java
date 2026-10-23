package com.agenticai.core.common.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/** The system prompt of the novel-failure explanation call is the registry key {@code diagnostics.technical_explanation}. */
class LlmBackedTechnicalStrategyPromptTest {

    private static final String GOLDEN =
            "You are an on-call engineering assistant explaining an application failure that has\n"
                    + "already had all secrets, credentials, emails, phone numbers, IP addresses, and\n"
                    + "filesystem paths removed from it. You will never be shown real secret values - do not\n"
                    + "invent any.\n"
                    + "\n"
                    + "Produce exactly two lines, in this exact format and no other text:\n"
                    + "TECHNICAL: <one or two sentences for a technical/developer audience - the real error\n"
                    + "category and likely cause, no raw stack traces>\n"
                    + "EXPERT: <maximum useful detail for an on-call engineer - exception class names,\n"
                    + "provider/tool names, config key names, likely root cause and next diagnostic step -\n"
                    + "still no secret VALUES, only names/categories>\n";

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private String systemSent(boolean withResolver) {
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(client.prompt().system(any(String.class)).user(any(String.class)).call().content())
                .thenReturn("TECHNICAL: a\nEXPERT: b");
        LlmBackedTechnicalStrategy strategy = new LlmBackedTechnicalStrategy();
        if (withResolver) {
            strategy.setPromptResolver(resolver);
        }
        LlmBackedTechnicalStrategy.TechnicalExplanation out =
                strategy.explain(new MaskedFailureContext("WeirdException", "x.WeirdException", "boom", null, null, Map.of(), client));
        assertThat(out.llmGenerated()).isTrue();
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(client.prompt(), atLeastOnce()).system(system.capture());
        return system.getValue();
    }

    @Test
    void theShippedPromptIsByteIdenticalToTheTextBlockItReplaced() {
        assertThat(PromptResolver.defaultsOnly().text(LlmBackedTechnicalStrategy.SYSTEM_PROMPT_KEY)).isEqualTo(GOLDEN);
        assertThat(systemSent(false)).isEqualTo(GOLDEN);
    }

    @Test
    void aTenantOverrideReachesTheRealCallPath() {
        TenantContext.set(TenantContext.of("acme", "u1", Set.of()));
        store.put(new PromptOverride(PromptScope.TENANT, "", LlmBackedTechnicalStrategy.SYSTEM_PROMPT_KEY, "en", "Explain tersely.\nTECHNICAL: <t>\nEXPERT: <e>", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        assertThat(systemSent(true)).startsWith("Explain tersely.");
    }
}
