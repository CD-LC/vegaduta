package com.agenticai.core.agents.turn.quality;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * Reflexion (Shinn et al.): after an answer is
 * produced (and possibly already revised once by {@link AnswerConsistencyChecker}), a second,
 * stricter critique asks not "is this internally consistent" but "did this actually solve what
 * the user asked" - a different, harder question a same-pass consistency check can miss (an
 * answer can be perfectly self-consistent and still incomplete or off-target).
 *
 * <p><b>Deliberately text-only, never a full tool-enabled re-run.</b> Classical Reflexion retries
 * the entire task attempt. This platform's tools include side-effecting actions (send an email,
 * post a message, spend money) already gated by an approval/guardrail system built around being
 * invoked once per genuine user request - re-invoking the whole tool-calling turn a second time
 * on every Reflexion retry would risk duplicate side effects for exactly the highest-stakes tool
 * calls. Scoped down instead to a bounded, tool-free revision pass over the text only, reusing
 * the tool results already reflected in {@code candidateAnswer} rather than re-fetching them -
 * this trades "can't fix a wrong tool choice via this mechanism" for "can never double-fire a
 * side effect from a quality check," the safer trade for the RIGOROUS tier specifically.
 *
 * <p>Only wired in for {@link ReasoningDepth#RIGOROUS} (see {@code AgentChatService}) - the
 * strictest tier, same convention {@code ChatClientToolCallVerifierService}'s tool-call check
 * already established (RIGOROUS-only; FAST and CAREFUL unaffected by this specific check).
 */
@Service
public class ReflexionService {

    private static final Logger log = LoggerFactory.getLogger(ReflexionService.class);

    private static final String CRITIQUE_SYSTEM_PROMPT = """
        You are an independent reviewer checking whether an AI assistant's answer actually solves
        what the user asked - not just whether it's well-written or internally consistent, but
        whether it is COMPLETE and ON-TARGET for the specific request. When tool-call evidence
        and/or recent earlier turns from this session are provided, also check that the answer
        accurately reflects what the tools actually returned and doesn't contradict anything
        already said earlier in this session.

        Respond with EXACTLY one of:
        SOLVED
        INCOMPLETE: <one short sentence on what's missing or off-target>

        No other text.
        """;

    private static final String REFLECT_AND_REVISE_SYSTEM_PROMPT = """
        You previously answered a user's request. An independent reviewer found the answer
        incomplete or off-target. Reflect on what was missed, then produce a complete, corrected
        final answer using only the information already available to you (do not claim to look
        anything up again). Output ONLY the corrected answer - no preamble, no meta-commentary
        about the revision itself.
        """;

    /**
     * Returns {@code candidateAnswer} unchanged unless the critique finds a genuine gap AND the
     * bounded revision succeeds - fails open at every step. Exactly one revision attempt is ever
     * made per turn; the revision itself is never re-critiqued (no recursive loop).
     */
    public String critiqueAndMaybeRevise(ChatClient chatClient, String userMessage, String candidateAnswer) {
        return critiqueAndMaybeRevise(chatClient, userMessage, candidateAnswer, null, null);
    }

    /**
     * Same as {@link #critiqueAndMaybeRevise(ChatClient, String, String)}, extended with two
     * optional grounding inputs (verification-audit fix, 2026-08-09, items 5+16 - see
     * {@code AgentChatService#verifyThenRespond}'s javadoc for the full finding). Both are
     * appended to the critique AND the revision prompt when present, matching
     * {@link AnswerConsistencyChecker#checkAndRevise(ChatClient, String, String, String, String)}'s
     * same shape.
     *
     * @param toolEvidence this turn's actual tool-call results, or {@code null}/blank if no tool
     *     ran - lets this stricter completeness check also catch a "solved but wrong" answer that
     *     misrepresents what a tool actually returned, not just an incomplete one.
     * @param recentHistory a bounded window of this session's prior turns, or {@code null}/blank
     *     if there's no prior history.
     */
    public String critiqueAndMaybeRevise(
        ChatClient chatClient, String userMessage, String candidateAnswer, String toolEvidence, String recentHistory
    ) {
        return critiqueAndMaybeRevise(chatClient, userMessage, candidateAnswer, toolEvidence, recentHistory, null, null);
    }

    /**
     * Same as {@link #critiqueAndMaybeRevise(ChatClient, String, String, String, String)},
     * plus the turn's agent/session for cost attribution of the extra completions RIGOROUS
     * pays for - see {@link LlmMetricsAdvisor#attributeTo}. Both may be null.
     */
    public String critiqueAndMaybeRevise(
        ChatClient chatClient, String userMessage, String candidateAnswer, String toolEvidence,
        String recentHistory, UUID agentId, String sessionId
    ) {
        if (chatClient == null || candidateAnswer == null || candidateAnswer.isBlank()) {
            return candidateAnswer;
        }
        try {
            String verdict = chatClient.prompt()
                .system(CRITIQUE_SYSTEM_PROMPT)
                .user(groundedPrompt(
                    "User's request: " + userMessage + "\n\nAnswer given: " + candidateAnswer,
                    toolEvidence, recentHistory))
                .advisors(LlmMetricsAdvisor.attributeTo(agentId, sessionId))
                .call()
                .content();

            if (verdict == null || verdict.isBlank()
                || !verdict.trim().regionMatches(true, 0, "INCOMPLETE", 0, "INCOMPLETE".length())) {
                return candidateAnswer;
            }

            String trimmedVerdict = verdict.trim();
            String gap = trimmedVerdict.length() > "INCOMPLETE:".length()
                ? trimmedVerdict.substring(trimmedVerdict.indexOf(':') + 1).trim()
                : "the answer did not fully address the request";
            log.info("Reflexion critique found a gap - revising once (bounded, no re-critique): {}", gap);

            String revised = chatClient.prompt()
                .system(REFLECT_AND_REVISE_SYSTEM_PROMPT)
                .user(groundedPrompt(
                    "Original request: " + userMessage + "\n\nPrevious answer: " + candidateAnswer
                        + "\n\nGap found: " + gap,
                    toolEvidence, recentHistory))
                .advisors(LlmMetricsAdvisor.attributeTo(agentId, sessionId))
                .call()
                .content();

            return (revised == null || revised.isBlank()) ? candidateAnswer : revised.trim();
        } catch (Exception ex) {
            log.warn("Reflexion critique failed - returning the answer unrevised", ex);
            return candidateAnswer;
        }
    }

    /** Appends the optional evidence/history blocks used by both {@link #critiqueAndMaybeRevise} calls, when present. */
    private static String groundedPrompt(String base, String toolEvidence, String recentHistory) {
        StringBuilder prompt = new StringBuilder(base);
        if (toolEvidence != null && !toolEvidence.isBlank()) {
            prompt.append("\n\nTool-call evidence the answer was built from:\n").append(toolEvidence);
        }
        if (recentHistory != null && !recentHistory.isBlank()) {
            prompt.append("\n\nEarlier in this session:\n").append(recentHistory);
        }
        return prompt.toString();
    }
}
