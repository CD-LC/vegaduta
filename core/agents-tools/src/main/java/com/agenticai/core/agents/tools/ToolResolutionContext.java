package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

/**
 * Everything one tool resolution hands to every {@link ToolSource}: the same values the monolithic
 * {@code AgentToolProvider.resolveToolsFor} used to pass to each {@code resolveXxx} helper.
 *
 * @param stepCounter one counter per chat turn, shared by every tool of that turn (the guardrail
 *     ceiling is "N calls this turn", not per tool)
 * @param rigorous whether RIGOROUS-mode tool-call verification applies to this turn
 * @param allowDelegation false for a delegated call, so a collaborator is never offered a further
 *     delegation tool
 * @param mainChatTurn true only for a real main chat turn (the one path that opens a compaction
 *     run)
 * @param resolvedSoFar the allow-list-filtered tools resolved before a {@link
 *     ToolSource.Kind#POST_ALLOW_LIST} source runs; empty for every other kind
 */
record ToolResolutionContext(
        UUID agentId,
        String sessionId,
        String subjectKey,
        AtomicInteger stepCounter,
        String userMessage,
        ChatClient chatClient,
        boolean rigorous,
        boolean allowDelegation,
        boolean mainChatTurn,
        List<ToolCallback> resolvedSoFar) {

    ToolResolutionContext withResolved(List<ToolCallback> resolved) {
        return new ToolResolutionContext(
                agentId,
                sessionId,
                subjectKey,
                stepCounter,
                userMessage,
                chatClient,
                rigorous,
                allowDelegation,
                mainChatTurn,
                resolved);
    }
}
