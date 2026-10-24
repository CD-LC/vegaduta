package com.agenticai.core.agents.lab;

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

/** The lab judge prompts come from the registry, per agent, on the real {@code score} / {@code same} paths. */
class AgentLabJudgePromptOverrideTest {

    private final AgentLabCompletionService completion = mock(AgentLabCompletionService.class);
    private final AgentLabJudge judge = new AgentLabJudge(completion);
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());
    private final UUID agentId = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void anAgentOverrideChangesBothJudgePromptsForThatAgentOnly() {
        judge.setPromptResolver(resolver);
        when(completion.complete(any())).thenReturn("SCORE: 0.5\nFEEDBACK: ok", "SAME");
        TenantContext.set(TenantContext.of("a", "owner", Set.of()));
        store.put(new PromptOverride(PromptScope.AGENT, agentId.toString(), "lab.judge.score", "en", "Grade harshly.\nSCORE: <n>", true, 1, "owner", Instant.now()));
        store.put(new PromptOverride(PromptScope.AGENT, agentId.toString(), "lab.judge.same", "en", "SAME or DIFFERENT?", true, 1, "owner", Instant.now()));
        resolver.evictAll();

        judge.score(agentId, "u", "lab:1", "q", null, "a");
        judge.same(agentId, "u", "lab:2", "q", "a", "b");
        judge.same(UUID.randomUUID(), "u", "lab:3", "q", "a", "b");

        ArgumentCaptor<LabCompletionRequest> sent = ArgumentCaptor.forClass(LabCompletionRequest.class);
        verify(completion, times(3)).complete(sent.capture());
        assertEquals("Grade harshly.\nSCORE: <n>", sent.getAllValues().get(0).systemPrompt());
        assertEquals("SAME or DIFFERENT?", sent.getAllValues().get(1).systemPrompt());
        assertEquals(AgentLabJudge.SAME_SYSTEM_PROMPT, sent.getAllValues().get(2).systemPrompt());
    }
}
