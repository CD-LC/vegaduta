package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.security.TenantContext;
import java.security.Principal;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Which console turns carry a turn reference (and so can be varied by the RL bandits) is the setting {@code
 * rl.selection.server-turn-ref}, applied at {@code POST /api/agents/{agentId}/chat}: as shipped only a turn the
 * client tagged; when on, an untagged turn gets a server-made reference.
 */
class AgentChatStreamControllerRlTurnRefTest {

    private final AtomicReference<ChatTurnRequest> sent = new AtomicReference<>();
    private AgentChatStreamController controller;

    @BeforeEach
    void setUp() {
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class))).thenAnswer(inv -> {
            sent.set(inv.getArgument(1));
            return Flux.just("ok");
        });
        controller = new AgentChatStreamController(mock(AgentService.class), chatService, Duration.ofSeconds(15));
        TenantContext.set(TenantContext.of("tenant_acme", "user-1", Set.of("USER")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void chat(String turnRef) {
        Principal principal = () -> "user-1";
        controller.chat(UUID.randomUUID(),
                new ChatRequest("session-1", "hi", null, null, turnRef, null, null, false, false), principal, null)
            .getBody().collectList().block(Duration.ofSeconds(5));
    }

    @Test
    void asShippedAnUntaggedTurnHasNoReferenceAndNeverReachesTheBandits() {
        controller.setRlTurnRefPolicy(new RlTurnRefPolicy(false));

        chat(null);

        assertThat(sent.get().turnRef()).isNull();
    }

    @Test
    void asShippedThePlaygroundsTurnReferenceStillGoesThrough() {
        controller.setRlTurnRefPolicy(new RlTurnRefPolicy(false));

        chat("playground-1");

        assertThat(sent.get().turnRef()).isEqualTo("playground-1");
    }

    @Test
    void whenEnabledAnUntaggedConsoleTurnGetsAServerMadeReference() {
        controller.setRlTurnRefPolicy(new RlTurnRefPolicy(true));

        chat(null);

        assertThat(sent.get().turnRef()).startsWith(RlTurnRefPolicy.SERVER_PREFIX);
    }

    @Test
    void withoutThePolicyBeanTheClientsReferenceIsUsedUnchanged() {
        chat(null);
        assertThat(sent.get().turnRef()).isNull();
        chat("t-9");
        assertThat(sent.get().turnRef()).isEqualTo("t-9");
    }
}
