package com.agenticai.core.agents.turn;

import java.util.UUID;
import org.springframework.test.util.AopTestUtils;

/**
 * Test-only window onto the REAL {@code ChatMemoryRecall} a wired {@link AgentChatService} holds (package-private), for
 * the real-platform memory eval ({@code PlatformMemoryEvalSubject}): the exact prompt block a chat turn would get and
 * the recall trace of that turn (private, shared and unified ids, never text), produced through the real recall switch,
 * the settings catalog, the legacy fact recall and the unified recall composition.
 */
public final class PlatformRecallBridge {

    /** The block (null when nothing was injected or recall is off), the facts counted and the turn's recall trace. */
    public record Recall(String block, int recalledFacts, TurnMemoryRecall.Trace trace) {}

    private PlatformRecallBridge() {}

    /**
     * Replaces how the recall asks "is memory recall on for this agent". The eval installs the real settings catalog's answer
     * read inside a transaction, because the catalog's store reads unqualified tables and outside a transaction sees
     * {@code public} instead of the tenant (Addendum E, defect D1); everything else of the recall stays as wired.
     */
    public static void useRecallSwitch(Object chatService, java.util.function.Predicate<UUID> recallEnabled) {
        AgentChatService target = AopTestUtils.getUltimateTargetObject(chatService);
        target.memoryRecall.useRecallSwitch(recallEnabled);
    }

    public static Recall recall(Object chatService, UUID agentId, String subject, String userMessage) {
        AgentChatService target = AopTestUtils.getUltimateTargetObject(chatService);
        ChatMemoryRecall.MemoryRecall r = target.memoryRecall.recallMemoryFacts(agentId, subject, userMessage, null);
        return new Recall(r.block(), r.recalledFacts(), r.trace());
    }
}
