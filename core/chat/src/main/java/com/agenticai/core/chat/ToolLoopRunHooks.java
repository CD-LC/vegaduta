package com.agenticai.core.chat;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * The per-run hooks {@link ArgumentSanitizingToolCallingManager} applies after a tool round when the
 * request's tool context carries them: the run's {@link ToolLoopHistoryTransform} (token-budget
 * tool-result clearing and compaction, {@code app.context.compaction.*}) and its {@link
 * ToolLoopTranscript} (the real conversation, handed back to the turn). A request without the keys -
 * every request of a caller that does not use them - is unaffected, and a hook that fails never costs
 * the turn.
 */
final class ToolLoopRunHooks {

    private static final Logger log = LoggerFactory.getLogger(ToolLoopRunHooks.class);

    private ToolLoopRunHooks() {}

    /** The history after the run's own transform, or {@code history} when there is none or it fails. */
    static List<Message> transform(Prompt prompt, List<Message> history) {
        if (!(toolContext(prompt).get(ToolLoopHistoryTransform.TOOL_CONTEXT_KEY) instanceof ToolLoopHistoryTransform transform)) {
            return history;
        }
        try {
            List<Message> transformed = transform.transform(history, ((ToolCallingChatOptions) prompt.getOptions()).getModel());
            return transformed != null ? transformed : history;
        } catch (RuntimeException e) {
            log.warn("Tool-loop history transform failed; sending the history unchanged: {}", e.toString());
            return history;
        }
    }

    /** Hands {@code history}, what the next round is sent, to the run's transcript when it has one. */
    static void record(Prompt prompt, List<Message> history) {
        if (toolContext(prompt).get(ToolLoopTranscript.TOOL_CONTEXT_KEY) instanceof ToolLoopTranscript transcript) {
            transcript.record(history);
        }
    }

    /** Rounds earlier passes of the same run already spent, carried by the run's transcript; 0 for a lone invocation. */
    static int roundsSpentBefore(Prompt prompt) {
        if (toolContext(prompt).get(ToolLoopTranscript.TOOL_CONTEXT_KEY) instanceof ToolLoopTranscript transcript) {
            return transcript.roundsSpentBefore();
        }
        return 0;
    }

    /** The round cap this run asked for through its transcript, or {@code fallback} when it asked for none. */
    static int maxRounds(Prompt prompt, int fallback) {
        if (toolContext(prompt).get(ToolLoopTranscript.TOOL_CONTEXT_KEY) instanceof ToolLoopTranscript transcript) {
            Integer own = transcript.maxRounds();
            if (own != null) {
                return own;
            }
        }
        return fallback;
    }

    /** {@code base} with the run's own clearing switch and threshold applied, or {@code base} when it asked for none. */
    static ToolResultClearing clearing(Prompt prompt, ToolResultClearing base) {
        if (toolContext(prompt).get(ToolLoopTranscript.TOOL_CONTEXT_KEY) instanceof ToolLoopTranscript transcript) {
            return base.withOverrides(transcript.clearingEnabled(), transcript.clearingThresholdChars());
        }
        return base;
    }

    /** The agent and tenant the run says its loop text is for, or {@link ChatPrompts.Run#NONE}. */
    static ChatPrompts.Run promptRun(Prompt prompt) {
        if (toolContext(prompt).get(ToolLoopTranscript.TOOL_CONTEXT_KEY) instanceof ToolLoopTranscript transcript) {
            return transcript.promptRun();
        }
        return ChatPrompts.Run.NONE;
    }

    private static Map<String, Object> toolContext(Prompt prompt) {
        if (prompt != null && prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null) {
            return options.getToolContext();
        }
        return Map.of();
    }
}
