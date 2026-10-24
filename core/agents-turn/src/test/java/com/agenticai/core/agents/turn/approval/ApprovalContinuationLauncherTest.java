package com.agenticai.core.agents.turn.approval;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Flux;

class ApprovalContinuationLauncherTest {

    private static final String TENANT = "tenant-a";
    private final AgentChatService chat = mock(AgentChatService.class);
    private final ApprovalContinuationFactory factory = mock(ApprovalContinuationFactory.class);
    private final ApprovalContinuationProperties properties = new ApprovalContinuationProperties();
    private final Set<String> claimed = new HashSet<>();
    private final ApprovalRequest request = new ApprovalRequest("mcp_tool.send_invoice", "s", "{}", "r");
    private ApprovalContinuationLauncher launcher;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private ApprovalContinuationLauncher launcher() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> claimed.add(inv.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(inv -> claimed.remove(inv.<String>getArgument(0)));
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        launcher =
                new ApprovalContinuationLauncher(
                        chat,
                        factory,
                        new ApprovalContinuationPrompt(properties),
                        new ApprovalContinuationClaims(provider, properties),
                        properties);
        return launcher;
    }

    private static ApprovalContinuation continuation(String sessionId, String tenant) {
        UUID agent = UUID.randomUUID();
        return new ApprovalContinuation(
                UUID.randomUUID(),
                Outcome.COMPLETED,
                "send_invoice",
                "{}",
                "sent",
                "admin-1",
                "Send the invoice.",
                agent,
                sessionId,
                "user-1",
                tenant,
                "user-1",
                SessionMemoryService.scopedSessionId(tenant, agent, "user-1", sessionId),
                List.of("send_invoice"),
                List.of());
    }

    private void built(ApprovalContinuation c) {
        when(factory.build(any(), any(), any())).thenReturn(Optional.of(c));
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class))).thenReturn(Flux.just("done"));
    }

    @Test
    void theJobSessionPrefixIsTheOneTheJobModuleUses() {
        assertThat(ApprovalContinuationLauncher.JOB_SESSION_PREFIX)
                .isEqualTo(com.agenticai.core.agents.job.AgentJobSession.PREFIX);
    }

    @Test
    void itStartsAnOrdinaryTurnForTheSameAgentSessionAndPersonWithTheContinuationMessage() {
        ApprovalContinuation c = continuation("sess-1", TENANT);
        built(c);
        TenantContext.set(TenantContext.of(TENANT, "admin-1", Set.of("ROLE_x")));

        boolean started = launcher().afterDecision(request, Outcome.COMPLETED, "sent");

        assertThat(started).isTrue();
        ArgumentCaptor<TurnContext> context = ArgumentCaptor.forClass(TurnContext.class);
        ArgumentCaptor<ChatTurnRequest> turn = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chat, timeout(5000)).chat(context.capture(), turn.capture());
        assertThat(turn.getValue().agentId()).isEqualTo(c.requestedAgentId());
        assertThat(turn.getValue().sessionId()).isEqualTo("sess-1");
        assertThat(turn.getValue().subjectKey()).isEqualTo("user-1");
        assertThat(turn.getValue().continuation()).isSameAs(c);
        assertThat(ApprovalContinuation.isContinuationMessage(turn.getValue().userMessage())).isTrue();
        assertThat(turn.getValue().userMessage()).contains("Send the invoice.").contains("sent");
        // As the person who asked, with no roles - never as the approver.
        assertThat(context.getValue().tenantId()).isEqualTo(TENANT);
        assertThat(context.getValue().subject()).isEqualTo("user-1");
        assertThat(context.getValue().tenantContext().orElseThrow().getRoles()).isEmpty();
        assertThat(context.getValue().continuation()).isSameAs(c);
        // The approving thread keeps its own context.
        assertThat(TenantContext.current().orElseThrow().getSubject()).isEqualTo("admin-1");
    }

    @Test
    void theSameDecisionIsContinuedOnce() {
        built(continuation("sess-1", TENANT));
        ApprovalContinuationLauncher launcher = launcher();

        assertThat(launcher.afterDecision(request, Outcome.COMPLETED, "sent")).isTrue();
        assertThat(launcher.afterDecision(request, Outcome.COMPLETED, "sent")).isFalse();
        assertThat(launcher.afterDecision(request, Outcome.COMPLETED, "sent")).isFalse();

        verify(chat, timeout(5000).times(1)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void aJobSessionIsLeftToTheJobMachinery() {
        built(continuation("job:" + UUID.randomUUID(), TENANT));

        assertThat(launcher().afterDecision(request, Outcome.COMPLETED, "sent")).isFalse();

        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        assertThat(claimed).isEmpty();
    }

    @Test
    void aRequestOfAnotherTenantThanTheDeciderIsNotContinued() {
        built(continuation("sess-1", "tenant-b"));
        TenantContext.set(TenantContext.of(TENANT, "admin-1", Set.of()));

        assertThat(launcher().afterDecision(request, Outcome.COMPLETED, "sent")).isFalse();

        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        assertThat(claimed).isEmpty();
    }

    @Test
    void disabledMeansNothingContinues() {
        properties.setEnabled(false);
        built(continuation("sess-1", TENANT));

        assertThat(launcher().afterDecision(request, Outcome.COMPLETED, "sent")).isFalse();

        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void aRequestThatCannotBeContinuedStartsNothing() {
        when(factory.build(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(launcher().afterDecision(request, Outcome.COMPLETED, "sent")).isFalse();

        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void aTurnThatFailsOnTheWorkerNeverThrowsIntoTheApprovingThread() {
        built(continuation("sess-1", TENANT));
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class))).thenThrow(new IllegalStateException("boom"));
        ApprovalContinuationLauncher launcher = launcher();

        // The failure surfaces on the worker (the Flux is deferred), not on the approving thread.
        boolean started = launcher.afterDecision(request, Outcome.COMPLETED, "sent");

        assertThat(started).isTrue();
        verify(chat, timeout(5000).times(1)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void aFactoryFailureIsSwallowed() {
        when(factory.build(any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        assertThat(launcher().afterDecision(request, Outcome.COMPLETED, "sent")).isFalse();

        verify(chat, times(0)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }
}
