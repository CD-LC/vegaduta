package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.guardrails.taint.SenderTrust;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.UUID;

/** Builds the chat turn and its context for {@link DemoStudioService}'s missions. */
final class DemoStudioTurns {

    private DemoStudioTurns() {}

    /** The studio's turn shape: empty attachment and extra-tool lists, live step events. */
    static ChatTurnRequest studioTurn(
            UUID agentId,
            String chatSessionId,
            String subject,
            String text,
            AgentStepEventEmitter stepEvents) {
        return new ChatTurnRequest(
                agentId,
                chatSessionId,
                subject,
                text,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                false,
                stepEvents,
                false);
    }

    /**
     * ADR-0022 stage 4: built inside {@code inDemoTenantContextAs}, so the turn context carries the
     * demo tenant that call put on this thread.
     */
    static TurnContext demoTurnContext(ChatTurnRequest turn) {
        TurnContext context = TurnContext.forEntry(TenantContext.current().orElse(null), turn);
        // Anonymous visitor of the public demo studio.
        SenderTrust.UNTRUSTED.seed(context.taint(), "inbound:demo_studio");
        return context;
    }
}
