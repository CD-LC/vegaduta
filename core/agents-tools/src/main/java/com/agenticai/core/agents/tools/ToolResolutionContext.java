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
 * @param stickyTools tool names relevance selection must keep on offer whatever the message says:
 *     the tools the conversation already used, on the continuation turn after an approval (empty on
 *     every other turn)
 * @param forceToolDeferral the serving row's own on-demand-tool-loading switch (V674, {@code
 *     PlatformHostedLlmProvider#getToolDeferral} via {@code TurnModelTraits#toolDeferral}): true
 *     applies {@link ToolSearchDeferral} to this resolution even when {@code
 *     app.tools.search.enabled} is off
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
        List<ToolCallback> resolvedSoFar,
        java.util.Set<String> stickyTools,
        boolean forceToolDeferral) {

    ToolResolutionContext {
        stickyTools = stickyTools == null ? java.util.Set.of() : java.util.Set.copyOf(stickyTools);
    }

    /** Pre-tool-deferral shape (sticky tools, no per-model override), kept for existing callers. */
    ToolResolutionContext(
            UUID agentId,
            String sessionId,
            String subjectKey,
            AtomicInteger stepCounter,
            String userMessage,
            ChatClient chatClient,
            boolean rigorous,
            boolean allowDelegation,
            boolean mainChatTurn,
            List<ToolCallback> resolvedSoFar,
            java.util.Set<String> stickyTools) {
        this(
                agentId,
                sessionId,
                subjectKey,
                stepCounter,
                userMessage,
                chatClient,
                rigorous,
                allowDelegation,
                mainChatTurn,
                resolvedSoFar,
                stickyTools,
                false);
    }

    /**
     * A context with no sticky tools (every turn but the continuation after an approval) and no
     * per-model tool-deferral override.
     */
    ToolResolutionContext(
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
        this(
                agentId,
                sessionId,
                subjectKey,
                stepCounter,
                userMessage,
                chatClient,
                rigorous,
                allowDelegation,
                mainChatTurn,
                resolvedSoFar,
                java.util.Set.of(),
                false);
    }

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
                resolved,
                stickyTools,
                forceToolDeferral);
    }
}
