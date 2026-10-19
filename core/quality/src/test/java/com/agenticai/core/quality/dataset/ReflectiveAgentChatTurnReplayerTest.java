package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agenticai.core.agents.turn.AgentChatService;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

class ReflectiveAgentChatTurnReplayerTest {

    @Test
    void toollessReplayCollectsAFluxWhoseRuntimeClassIsNotPublic() throws Exception {
        GenericApplicationContext ctx = new GenericApplicationContext();
        ctx.registerBean(AgentChatService.class);
        ctx.refresh();
        ReflectiveAgentChatTurnReplayer replayer = new ReflectiveAgentChatTurnReplayer(ctx);

        AgentTurnReplayer.ReplayedTurn turn =
                replayer.replay(UUID.randomUUID(), "s", "k", "there", false, Duration.ofSeconds(5));

        assertEquals("hi there", turn.responseText());
    }
}
