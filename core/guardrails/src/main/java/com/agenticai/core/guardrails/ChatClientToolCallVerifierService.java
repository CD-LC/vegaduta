package com.agenticai.core.guardrails;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * V1 {@link ToolCallVerifierService} - a single short, non-streamed prompt
 * asking the model to judge its OWN about-to-happen tool call against the
 * user's message, deliberately kept to one cheap round-trip rather than a
 * multi-turn debate (matches Anthropic's own "maintain simplicity" building-
 * effective-agents principle - this is the smallest version of the
 * Evaluator-Optimizer pattern that's still a genuine independent check, not
 * the model grading its own homework in the same breath as deciding to act).
 *
 * <p>Response format is intentionally rigid ({@code APPROVED} or
 * {@code REJECTED: <reason>} as the first line) so parsing doesn't need a
 * second model call or a JSON schema - a plain prefix check is enough and
 * fails safe (see {@link #verify}'s catch block and the "doesn't start with
 * REJECTED" branch) if the model ever answers in some other shape.
 */
@Service
public class ChatClientToolCallVerifierService implements ToolCallVerifierService {

    private static final Logger log = LoggerFactory.getLogger(ChatClientToolCallVerifierService.class);

    /**
     * Cap on how much of the user message / tool arguments are embedded into
     * the verifier prompt. Same rationale and value as {@code
     * ToxicityGuardrail}'s cap - an oversized message or argument payload
     * could otherwise push this call's own judging instructions out of the
     * model's effective attention window.
     */
    private static final int MAX_JUDGE_INPUT_CHARS = 4000;

    private static final String VERIFIER_SYSTEM_PROMPT = """
        You are an independent safety check, not the agent that decided to make this tool call.
        You will be shown the user's message and a tool call another AI is about to make in response.
        Judge ONLY whether the tool call plausibly matches what the user actually asked for.
        Do not judge tool syntax, do not re-answer the user's question yourself.

        Respond with EXACTLY one of:
        APPROVED
        REJECTED: <one short sentence explaining the mismatch>

        No other text.
        """;

    @Override
    public VerificationResult verify(ChatClient chatClient, String userMessage, String toolName, String toolInput) {
        if (chatClient == null) {
            // No chat client to verify with (e.g. the tool-discovery probe
            // path in AgentToolProvider.listAvailableToolsFor, which never
            // actually invokes a tool) - nothing to check, fail open.
            return VerificationResult.approved("no verification context available");
        }
        try {
            String verdict = chatClient.prompt()
                .system(VERIFIER_SYSTEM_PROMPT)
                .user(
                    "User's message: " + truncateForJudge(userMessage) + "\n\n"
                        + "Tool the agent is about to call: " + toolName + "\n"
                        + "Arguments: " + truncateForJudge(toolInput)
                )
                .call()
                .content();

            if (verdict == null || verdict.isBlank()) {
                return VerificationResult.approved("verifier returned no content - failing open");
            }
            String trimmed = verdict.trim();
            if (trimmed.regionMatches(true, 0, "REJECTED", 0, "REJECTED".length())) {
                String reason = trimmed.length() > "REJECTED:".length()
                    ? trimmed.substring(trimmed.indexOf(':') + 1).trim()
                    : "tool call did not appear to match the user's request";
                log.info("Tool-call verifier rejected '{}' - {}", toolName, reason);
                return VerificationResult.rejected(reason);
            }
            return VerificationResult.approved("verified against user's request");
        } catch (Exception ex) {
            // Fail open, deliberately - see this interface's javadoc. A
            // verifier outage must degrade to "no extra check this turn",
            // not "no tool calls work at all" for every RIGOROUS agent.
            log.warn("Tool-call verifier failed for tool '{}' - proceeding without verification this call", toolName, ex);
            return VerificationResult.approved("verifier unavailable - failed open");
        }
    }

    private static String truncateForJudge(String text) {
        if (text == null || text.length() <= MAX_JUDGE_INPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_JUDGE_INPUT_CHARS) + " …[truncated]";
    }
}
