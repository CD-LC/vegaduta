package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * The streamed-reply completion callback runs on a thread with no {@link TenantContext}.
 * LlmQualityJudgeService#sampleAsync snapshots the calling thread's context for the judge task, so
 * the callback must re-apply the context it captured on the request thread around that call.
 */
class ChatTurnExecutorStreamCompletionTenantTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void judgeIsSampledUnderTheCapturedTenantAndTheThreadIsCleanAfterwards() throws Exception {
        LlmQualityJudgeService judge = mock(LlmQualityJudgeService.class);
        List<Optional<String>> seenByJudge = new ArrayList<>();
        doAnswer(
                        inv -> {
                            seenByJudge.add(
                                    TenantContext.current().map(TenantContext::getTenantId));
                            return null;
                        })
                .when(judge)
                .sampleAsync(any(), any(), anyString(), anyString(), anyString());
        AgentMessageEvents events = mock(AgentMessageEvents.class);
        Optional<TenantContext> captured = Optional.of(TenantContext.of("tenant_a", "u", Set.of()));

        Optional<TenantContext> after =
                CompletableFuture.supplyAsync(
                                () -> {
                                    assertTrue(TenantContext.current().isEmpty());
                                    StreamedReplyCompletion.afterPersisted(
                                            events,
                                            judge,
                                            new StreamedReplyCompletion.Reply(
                                                    mock(ChatClient.class),
                                                    UUID.randomUUID(),
                                                    "s1",
                                                    "tenant_a",
                                                    "hi",
                                                    "hello",
                                                    "hello"),
                                            captured);
                                    return TenantContext.current();
                                })
                        .get();

        assertEquals(List.of(Optional.of("tenant_a")), seenByJudge);
        assertTrue(after.isEmpty(), "the completion thread must not keep the tenant's context");
    }
}
