package com.agenticai.core.agents.skills.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentLabCompletionService;
import com.agenticai.core.agents.turn.AgentLabCompletionService.LabCompletionRequest;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** The skill self-check judge reads its prompt from the registry, per agent, on the real {@code check} path. */
class SkillSelfCheckPromptOverrideTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAgentOverrideChangesThePromptForThatAgentOnly() {
        AgentLabCompletionService lab = mock(AgentLabCompletionService.class);
        when(lab.complete(any(LabCompletionRequest.class))).thenReturn("{\"verdict\":\"PASS\",\"reason\":\"ok\"}");
        SkillSelfChecker checker = new SkillSelfChecker(lab, mock(ObjectProvider.class));
        checker.setPromptResolver(resolver);
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.AGENT, AGENT.toString(), SkillSelfChecker.PROMPT_KEY, "en", "Judge the draft skill.", true, 1, "owner", Instant.now()));
        resolver.evictAll();

        checker.check(AGENT, "s1", "request", "answer", "name", "1. step", "alice");
        checker.check(UUID.randomUUID(), "s2", "request", "answer", "name", "1. step", "alice");

        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(lab, times(2)).complete(sent.capture());
        assertEquals("Judge the draft skill.", sent.getAllValues().get(0).systemPrompt());
        assertEquals(SkillSelfChecker.SYSTEM_PROMPT, sent.getAllValues().get(1).systemPrompt());
    }
}
