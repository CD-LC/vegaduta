package com.agenticai.core.agents.turn.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.settings.BehaviourCatalogFixture;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Flux;

/**
 * {@code approvals.continuation.enabled}: whether a human's decision makes the platform carry on with the task is the
 * tenant's or the agent's own choice when they made one, the {@code app.approvals.continuation.enabled} value when not.
 */
class ApprovalContinuationLauncherSettingsTest {

    private static final String TENANT = "tenant-a";
    private final AgentChatService chat = mock(AgentChatService.class);
    private final ApprovalContinuationFactory factory = mock(ApprovalContinuationFactory.class);
    private final BehaviourCatalogFixture f = new BehaviourCatalogFixture();
    private final Set<String> claimed = new HashSet<>();
    private final ApprovalRequest request = new ApprovalRequest("mcp_tool.send_invoice", "s", "{}", "r");
    private final UUID agent = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private ApprovalContinuationLauncher launcher(boolean withCatalog) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> claimed.add(inv.getArgument(0)));
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        ApprovalContinuationLauncher launcher =
                new ApprovalContinuationLauncher(
                        chat,
                        factory,
                        new ApprovalContinuationPrompt(f.approvalContinuation),
                        new ApprovalContinuationClaims(provider, f.approvalContinuation),
                        f.approvalContinuation);
        if (withCatalog) {
            launcher.setSettingsResolver(f.resolver());
        }
        ApprovalContinuation c =
                new ApprovalContinuation(
                        UUID.randomUUID(), Outcome.COMPLETED, "send_invoice", "{}", "sent", "admin-1", "Send the invoice.",
                        agent, "sess-1", "user-1", TENANT, "user-1",
                        SessionMemoryService.scopedSessionId(TENANT, agent, "user-1", "sess-1"),
                        List.of("send_invoice"), List.of());
        when(factory.requestedAgentId(request)).thenReturn(Optional.of(agent));
        when(factory.build(any(), any(), any())).thenReturn(Optional.of(c));
        when(chat.chat(any(TurnContext.class), any(ChatTurnRequest.class))).thenReturn(Flux.just("done"));
        return launcher;
    }

    private boolean decide(ApprovalContinuationLauncher launcher, String tenant) {
        claimed.clear();
        boolean[] started = new boolean[1];
        BehaviourCatalogFixture.inTenant(tenant, () -> started[0] = launcher.afterDecision(request, Outcome.COMPLETED, "sent"));
        return started[0];
    }

    @Test
    void withNoOverrideTheTaskContinuesAsThePlatformPropertySays() {
        assertThat(decide(launcher(true), TENANT)).isTrue();
        verify(chat, timeout(5000)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void aTenantThatTurnedContinuationOffGetsNoContinuationAndStartsNoTurn() {
        f.tenant(TENANT, "approvals.continuation.enabled", false);
        ApprovalContinuationLauncher launcher = launcher(true);

        assertThat(decide(launcher, TENANT)).isFalse();
        verify(chat, never()).chat(any(TurnContext.class), any(ChatTurnRequest.class));
        verify(factory, never()).build(any(), any(), any());
    }

    @Test
    void anAgentCanOptInWhereThePlatformAndItsTenantHaveContinuationOff() {
        f.approvalContinuation.setEnabled(false);
        f.agent(TENANT, agent, "approvals.continuation.enabled", true);
        ApprovalContinuationLauncher launcher = launcher(true);

        assertThat(decide(launcher, TENANT)).isTrue();
        verify(chat, timeout(5000)).chat(any(TurnContext.class), any(ChatTurnRequest.class));
    }

    @Test
    void anotherTenantsChoiceDoesNotApply() {
        f.tenant("tenant-b", "approvals.continuation.enabled", false);

        assertThat(decide(launcher(true), TENANT)).isTrue();
    }

    @Test
    void aLauncherBuiltWithoutACatalogReadsTheProperty() {
        f.tenant(TENANT, "approvals.continuation.enabled", false);
        ApprovalContinuationLauncher launcher = launcher(false);

        assertThat(decide(launcher, TENANT)).isTrue();
    }
}
