package com.agenticai.core.agents.turn.quality;

import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Completes {@link ReasoningDepth#CAREFUL}'s
 * long-standing placeholder ("reserved for a future finer-grained mode... currently behaves
 * identically to FAST").
 *
 * <p>Classic self-consistency (Wang et al.), adapted to open-ended chat answers rather than a
 * single verifiable final value, and adapted again to this platform's tool-calling agents
 * specifically: real self-consistency resamples the whole reasoning path N times and takes the
 * majority answer. Doing that naively here would re-run the ENTIRE tool-calling turn N times,
 * which would re-invoke every tool that turn used - including side-effecting ones (send an
 * email, post a message, spend money) - N times each. That is not an acceptable correctness
 * trade for a "check my answer more carefully" feature.
 *
 * <p>Instead: the turn runs ONCE as normal (tools execute exactly once, correctly), and this
 * class checks the ALREADY-PRODUCED answer for internal inconsistency or unsupported claims
 * relative to the user's question via one cheap, tool-free verification call - only issuing a
 * second, also tool-free, revision call if that check actually finds a problem. A solid first
 * answer costs exactly one extra cheap call; a flagged one costs two. Never a third.
 *
 * <p>Same rigid-response-format, fail-open shape as every other verifier in this codebase
 * ({@code ChatClientToolCallVerifierService}, {@code SemanticGuardrailService}) - a verifier
 * outage degrades to "return the original answer unchecked," never blocks the chat turn.
 */
@Service
public class AnswerConsistencyChecker {

    private static final Logger log = LoggerFactory.getLogger(AnswerConsistencyChecker.class);

    /** Registry keys of the two prompts (default text: {@code prompts/en/quality.consistency.*.md}). */
    static final String CHECK_KEY = "quality.consistency.check";
    static final String REVISE_KEY = "quality.consistency.revise";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so a tenant or agent override of the keys above applies; absent = shipped text. */
    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    /**
     * Returns {@code originalAnswer} unchanged unless the consistency check finds a genuine
     * problem AND the revision call itself succeeds - any failure at any step falls back to the
     * original, unrevised answer rather than risking replacing a good answer with an error or a
     * worse one.
     */
    public String checkAndRevise(ChatClient chatClient, String userMessage, String originalAnswer) {
        return checkAndRevise(chatClient, userMessage, originalAnswer, null, null);
    }

    /**
     * Same as {@link #checkAndRevise(ChatClient, String, String)}, extended with two optional
     * grounding inputs (verification-audit fix, 2026-08-09, items 5+16 - see
     * {@code AgentChatService#verifyThenRespond}'s javadoc for the full finding). Both are
     * appended to the check AND the revision prompt when present, so a revision (if one fires)
     * stays grounded in the same evidence rather than drifting back to text-only plausibility.
     *
     * @param toolEvidence this turn's actual tool-call results, or {@code null}/blank if no tool
     *     ran - without this, the check could only compare the answer's text against itself, so a
     *     plausible-sounding misrepresentation of what a tool actually returned always passed.
     * @param recentHistory a bounded window of this session's prior turns, or {@code null}/blank
     *     if there's no prior history - lets the check also catch a same-session contradiction,
     *     which a check against only the current question and answer structurally cannot see.
     */
    public String checkAndRevise(
        ChatClient chatClient, String userMessage, String originalAnswer, String toolEvidence, String recentHistory
    ) {
        return checkAndRevise(chatClient, userMessage, originalAnswer, toolEvidence, recentHistory, null, null);
    }

    /**
     * Same as {@link #checkAndRevise(ChatClient, String, String, String, String)}, plus the
     * turn's agent/session so the one or two extra completions this check costs are
     * attributable in {@code llm_call_log} instead of landing there with a null agent - see
     * {@link LlmMetricsAdvisor#attributeTo}. Both may be null.
     */
    public String checkAndRevise(
        ChatClient chatClient, String userMessage, String originalAnswer, String toolEvidence,
        String recentHistory, UUID agentId, String sessionId
    ) {
        if (chatClient == null || originalAnswer == null || originalAnswer.isBlank()) {
            return originalAnswer;
        }
        try {
            String verdict = chatClient.prompt()
                .system(prompts.text(CHECK_KEY, agentId))
                .user(groundedPrompt(
                    "User's question: " + userMessage + "\n\nAnswer already given: " + originalAnswer,
                    toolEvidence, recentHistory))
                .advisors(LlmMetricsAdvisor.attributeTo(agentId, sessionId))
                .call()
                .content();

            if (verdict == null || verdict.isBlank()
                || !verdict.trim().regionMatches(true, 0, "REVISE", 0, "REVISE".length())) {
                return originalAnswer;
            }

            String trimmedVerdict = verdict.trim();
            String issue = trimmedVerdict.length() > "REVISE:".length()
                ? trimmedVerdict.substring(trimmedVerdict.indexOf(':') + 1).trim()
                : "the answer did not fully address the question";
            log.info("Self-consistency check flagged an issue - revising once: {}", issue);

            String revised = chatClient.prompt()
                .system(prompts.text(REVISE_KEY, agentId))
                .user(groundedPrompt(
                    "Original question: " + userMessage + "\n\nPrevious answer: " + originalAnswer
                        + "\n\nIssue found: " + issue,
                    toolEvidence, recentHistory))
                .advisors(LlmMetricsAdvisor.attributeTo(agentId, sessionId))
                .call()
                .content();

            return (revised == null || revised.isBlank()) ? originalAnswer : revised.trim();
        } catch (Exception ex) {
            log.warn("Self-consistency check failed - returning the original, unrevised answer", ex);
            return originalAnswer;
        }
    }

    /** Appends the optional evidence/history blocks used by both {@link #checkAndRevise} calls, when present. */
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
