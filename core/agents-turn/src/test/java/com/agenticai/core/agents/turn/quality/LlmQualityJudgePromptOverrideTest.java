package com.agenticai.core.agents.turn.quality;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.langfuse.LangfuseScoreClient;
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
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The sampled quality judge resolves its system prompt through the registry on the real sampling path. The
 * client answers only to the wording the test expects; a judged sample is visible as a Langfuse score push.
 */
class LlmQualityJudgePromptOverrideTest {

    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final LangfuseScoreClient langfuse = mock(LangfuseScoreClient.class);
    private final ChatClient client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private LlmQualityJudgeService service() {
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        when(issues.getIfAvailable()).thenReturn(mock(PlatformIssueService.class));
        Executor direct = Runnable::run;
        LlmQualityJudgeService service =
                new LlmQualityJudgeService(issues, mock(LlmCallLogService.class), langfuse, 1.0, 600, direct);
        service.setPromptResolver(resolver);
        return service;
    }

    private void answerOnlyTo(String systemPrompt) {
        when(client.prompt().system(eq(systemPrompt)).user(anyString()).call().content()).thenReturn("OK");
    }

    private void judged(int times) {
        verify(langfuse, times(times)).pushScore(any(), any(), any(), eq("llm_quality_ok"), any(), any(), any());
    }

    @Test
    void theShippedWordingIsSentUntilAnAgentOverrideExists() {
        answerOnlyTo(PromptResolver.defaultsOnly().catalog().defaultText("judge.quality"));

        service().sampleAsync(client, AGENT, "s", "question", "answer");

        judged(1);
    }

    @Test
    void anAgentOverrideIsSentForThatAgentAndNotForAnother() {
        TenantContext.set(TenantContext.of("t1", "u1", Set.of()));
        store.put(new PromptOverride(PromptScope.AGENT, AGENT.toString(), "judge.quality", "en", "Judge sharply.", true, 1, "owner", Instant.now()));
        resolver.evictAll();
        answerOnlyTo("Judge sharply.");
        LlmQualityJudgeService service = service();

        service.sampleAsync(client, AGENT, "s", "question", "answer");
        judged(1);

        service.sampleAsync(client, UUID.randomUUID(), "s", "question", "answer");
        verify(langfuse, never()).pushScore(any(), any(), eq("s"), eq("llm_quality_ok"), any(), eq(0), any());
        judged(1);
    }
}
