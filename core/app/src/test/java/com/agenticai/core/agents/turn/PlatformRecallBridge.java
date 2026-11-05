package com.agenticai.core.agents.turn;

import java.util.UUID;
import org.springframework.test.util.AopTestUtils;

/**
 * Test-only window onto the REAL {@code ChatMemoryRecall} a wired {@link AgentChatService} holds
 * (package-private), for the real-platform memory eval ({@code PlatformMemoryEvalSubject}): the
 * exact prompt block a chat turn would get and the recall trace of that turn (private, shared and
 * unified ids, never text), produced through the real recall switch, the settings catalog, the
 * legacy fact recall and the unified recall composition.
 */
public final class PlatformRecallBridge {

    /**
     * The block (null when nothing was injected or recall is off), the facts counted and the turn's
     * recall trace.
     */
    public record Recall(String block, int recalledFacts, TurnMemoryRecall.Trace trace) {}

    private PlatformRecallBridge() {}

    public static Recall recall(
            Object chatService, UUID agentId, String subject, String userMessage) {
        AgentChatService target = AopTestUtils.getUltimateTargetObject(chatService);
        ChatMemoryRecall.MemoryRecall r =
                target.memoryRecall.recallMemoryFacts(agentId, subject, userMessage, null);
        return new Recall(r.block(), r.recalledFacts(), r.trace());
    }
}
