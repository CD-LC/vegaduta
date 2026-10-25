package com.agenticai.core.agents.turn;

import java.util.UUID;

/**
 * Builds the {@link com.agenticai.core.chat.ToolLoopTranscript} a turn's tool-loop run carries,
 * factored out of {@link ChatTurnExecutor} for size: the transcript carries this tenant's and
 * agent's tool-result clearing settings ({@code chat.toolLoop.resultClearing.*}, which the tool
 * loop applies instead of the process-wide ones) and the turn's own round cap when its tool-step
 * ceiling needs a larger backstop than the platform's (see {@code
 * AgentRuntimeSettingsResolver#toolLoopRounds}).
 */
final class TurnTranscripts {

    private static final String TOOL_LOOP_MAX_PROMPT_TOKENS_KEY = "chat.toolLoop.maxPromptTokensPerTurn";

    private TurnTranscripts() {}

    /** As {@link ChatTurnExecutor#newTranscript(TurnContext)} for the agent that answers (the routed one when an experiment chose a variant). */
    static com.agenticai.core.chat.ToolLoopTranscript create(
            ChatTurnExecutor turns, TurnContext context, UUID agentId) {
        com.agenticai.core.chat.ToolLoopTranscript transcript = new com.agenticai.core.chat.ToolLoopTranscript();
        if (context != null) {
            // null = the tool loop's own (platform) value; set only when the catalog answers for this tenant and agent.
            transcript.resultClearing(
                    turns.behaviour.boolOrNull(
                            ChatTurnExecutor.TOOL_RESULT_CLEARING_ENABLED_KEY, context.scope(), agentId),
                    turns.behaviour.integerOrNull(
                            ChatTurnExecutor.TOOL_RESULT_CLEARING_THRESHOLD_KEY, context.scope(), agentId));
            // Cumulative prompt-token cap for this run (chat.toolLoop.maxPromptTokensPerTurn); null = uncapped.
            Integer tokenCap =
                    turns.behaviour.integerOrNull(
                            TOOL_LOOP_MAX_PROMPT_TOKENS_KEY, context.scope(), agentId);
            transcript.promptTokenBudget(tokenCap == null ? null : tokenCap.longValue());
        }
        com.agenticai.core.agents.runtime.AgentRuntime resolver = turns.runtime;
        com.agenticai.core.agents.runtime.AgentRuntimeSettings settings = context == null ? null : context.runtimeSettings();
        if (resolver != null && settings != null) {
            transcript.limitRounds(resolver.toolLoopRounds(settings));
        }
        // The tool loop writes text of its own (the "tool budget used up" result, the closing reply, the answer to a
        // tool name that was never offered): it looks it up in the prompt registry for THIS turn's agent and tenant.
        if (context != null) {
            transcript.forPrompts(context.requestedAgentId(), context.scope());
        }
        return transcript;
    }
}
