package com.agenticai.core.agents.turn;

import java.util.List;
import java.util.UUID;
import org.springframework.test.util.AopTestUtils;

/**
 * Test-only window onto the REAL {@code ChatMemoryRecall} that a wired {@link AgentChatService} holds (package-private),
 * so the unified-memory journey IT can render the exact block a chat turn would put in the prompt, through the real
 * recall switch, settings and unified-recall wiring.
 */
public final class JourneyRecallBridge {

    /** What one pre-turn recall produced: the prompt block (null when none), counted facts, unified ids and a note. */
    public record Recalled(String block, int facts, List<TurnMemoryRecall.UnifiedItem> unified, String note) {}

    private JourneyRecallBridge() {}

    public static Recalled recall(Object chatService, UUID agentId, String subject, String userMessage) {
        AgentChatService target = AopTestUtils.getUltimateTargetObject(chatService);
        ChatMemoryRecall.MemoryRecall r = target.memoryRecall.recallMemoryFacts(agentId, subject, userMessage, null);
        TurnMemoryRecall.Trace trace = r.trace();
        return new Recalled(
                r.block(),
                r.recalledFacts(),
                trace == null ? List.of() : trace.unifiedItems(),
                trace == null ? null : trace.unifiedNote());
    }
}
