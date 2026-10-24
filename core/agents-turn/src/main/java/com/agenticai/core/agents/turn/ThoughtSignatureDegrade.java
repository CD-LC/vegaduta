package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.turn.TurnToolActivity.Snapshot;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a turn does when the provider STILL refuses its tool-call history with Gemini's "missing
 * thought_signature" error, shared by the streamed ({@link FastStreamRecovery}) and the buffered
 * ({@link BufferedTurnCalls}) paths.
 *
 * <p>The platform now sends the model's own function call back with the signature Gemini put on it, so
 * this should not happen. When it does, the turn used to be silently re-run with every tool removed, the
 * answer cached as an ordinary one, and the model shown nothing of the work the tools had already done -
 * so it could say anything about it. Now the one remaining option is taken in the open: the reply begins
 * with a notice that it was written without tools and which steps had already run, the model is given
 * what those tools returned, and the reply is kept out of the cache. {@code
 * app.chat.resilience.thought-signature-degrade=false} reports the provider's error instead.
 */
final class ThoughtSignatureDegrade {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private ThoughtSignatureDegrade() {}

    /**
     * The buffered path's degraded reply: counted, marked as not an answer, opened with the notice, and
     * written by {@code withoutTools} - which runs the model with NO tools on the user text it is given,
     * the message plus what the tools already returned since {@code turnStartedAt}.
     */
    static String answerBuffered(
            ChatTurnExecutor turns,
            Agent agent,
            TurnContext context,
            UUID agentId,
            String sessionId,
            Instant turnStartedAt,
            String userMessage,
            Function<String, String> withoutTools) {
        log.warn(
                "Provider {} refused the tool-call history of agent {} session {} (missing thought_signature):"
                        + " answering once WITHOUT tools, and saying so in the reply",
                agent.getProviderName(),
                agentId,
                sessionId);
        turns.flagToolsDisabledRetry(agent, null, context, agentId);
        String evidence = null;
        try {
            evidence = turns.collectToolEvidenceSince(agentId, sessionId, turnStartedAt);
        } catch (RuntimeException readFailure) {
            log.warn("Agent {} session {}: could not read the tool results", agentId, sessionId, readFailure);
        }
        String notice = notice(turns.resilience, context.toolActivity().snapshot());
        context.reply().mark(TurnReplyOutcome.Kind.INTERRUPTED);
        return withNotice(notice, withoutTools.apply(userText(turns.resilience, userMessage, evidence)));
    }

    /** The notice that opens the degraded reply, naming the steps that had already run. */
    static String notice(ChatResilienceProperties resilience, Snapshot ran) {
        return resilience.getThoughtSignatureNotice().replace("{tools}", DepthTimeoutReport.completed(resilience, ran));
    }

    /**
     * {@code userMessage} with what the tools already returned put after it, so the model answers from it
     * instead of from nothing; {@code userMessage} unchanged when no tool ran or the note is switched off.
     */
    static String userText(ChatResilienceProperties resilience, String userMessage, String evidence) {
        int limit = resilience.getThoughtSignatureEvidenceChars();
        if (evidence == null || evidence.isBlank() || limit <= 0) {
            return userMessage;
        }
        String shown = evidence.length() > limit ? evidence.substring(0, limit) + "..." : evidence;
        return userMessage + "\n\n" + resilience.getThoughtSignatureEvidenceNote().replace("{evidence}", shown);
    }

    /** The notice, a blank line, then the model's own text. */
    static String withNotice(String notice, String answer) {
        return answer == null || answer.isBlank() ? notice : notice + "\n\n" + answer;
    }
}
