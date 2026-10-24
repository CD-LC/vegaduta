package com.agenticai.core.guardrails;

import com.agenticai.core.prompts.PromptResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
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
 * second model call or a JSON schema - a plain prefix check is enough.
 * An errored or blank verifier follows {@code app.guardrails.tool-verifier.fail-mode}
 * (default: the global {@code app.guardrails.fail-mode}, closed); see
 * {@link #failedCheck}. Window and sampling: {@code app.guardrails.judge.*}.
 */
@Service
public class ChatClientToolCallVerifierService implements ToolCallVerifierService {

    private static final Logger log = LoggerFactory.getLogger(ChatClientToolCallVerifierService.class);

    private final GuardrailTuningProperties tuning;

    /** Code defaults (no {@link GuardrailTuningProperties} bean): 4,000-character window, inherited sampling, global fail mode. */
    public ChatClientToolCallVerifierService() {
        this(new GuardrailTuningProperties());
    }

    @Autowired
    public ChatClientToolCallVerifierService(GuardrailTuningProperties tuning) {
        this.tuning = tuning;
    }

    /**
     * Where the judge prompt ({@code judge.tool_verifier}) and the default rejection reason come from; the shipped
     * texts unless the application wires the registry. The calling agent is the one in {@link GuardrailPromptScope}.
     */
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
    }

    @Override
    public VerificationResult verify(ChatClient chatClient, String userMessage, String toolName, String toolInput) {
        return verify(chatClient, userMessage, null, toolName, toolInput);
    }

    /**
     * As the four-argument call, with the end of the conversation put in front of the judge when {@code
     * app.guardrails.tool-verifier.context-messages} is above zero and the caller has one. A tail that cannot be
     * read is no context: the call is judged on the message alone, as before the tail existed.
     */
    @Override
    public VerificationResult verify(
        ChatClient chatClient, String userMessage, ConversationTail conversationTail, String toolName, String toolInput
    ) {
        if (chatClient == null) {
            // No chat client to verify with (e.g. the tool-discovery probe
            // path in AgentToolProvider.listAvailableToolsFor, which never
            // actually invokes a tool) - nothing to check, fail open.
            return VerificationResult.approved("no verification context available");
        }
        try {
            String verdict = tuning.getJudge().apply(chatClient.prompt()
                .system(GuardrailPrompts.scopedText(prompts, GuardrailPrompts.JUDGE_TOOL_VERIFIER))
                .user(
                    contextBlock(conversationTail)
                        + "User's message: " + GuardrailSettings.truncateForJudge(tuning, userMessage) + "\n\n"
                        + "Tool the agent is about to call: " + toolName + "\n"
                        + "Arguments: " + GuardrailSettings.truncateForJudge(tuning, toolInput)
                ))
                .call()
                .content();

            if (verdict == null || verdict.isBlank()) {
                return failedCheck(toolName, "returned no content", null);
            }
            String trimmed = verdict.trim();
            if (trimmed.regionMatches(true, 0, "REJECTED", 0, "REJECTED".length())) {
                String reason = trimmed.length() > "REJECTED:".length()
                    ? trimmed.substring(trimmed.indexOf(':') + 1).trim()
                    : GuardrailPrompts.scopedText(prompts, GuardrailPrompts.VERIFIER_DEFAULT_REASON);
                log.info("Tool-call verifier rejected '{}' - {}", toolName, reason);
                return VerificationResult.rejected(reason);
            }
            return VerificationResult.approved("verified against user's request");
        } catch (Exception ex) {
            return failedCheck(toolName, "failed", ex);
        }
    }

    /**
     * The conversation tail with its registry header and a blank line after it, or "" when the setting is off,
     * there is no tail source, or the tail is empty or unreadable.
     */
    private String contextBlock(ConversationTail conversationTail) {
        int messages = tuning.getToolVerifier().getContextMessages();
        if (conversationTail == null || messages <= 0) {
            return "";
        }
        try {
            String tail = conversationTail.get(messages, GuardrailSettings.judgeMaxInputChars(tuning));
            if (tail == null || tail.isBlank()) {
                return "";
            }
            return GuardrailPrompts.scopedText(prompts, GuardrailPrompts.JUDGE_TOOL_VERIFIER_CONTEXT) + "\n"
                + tail.strip() + "\n\n";
        } catch (RuntimeException e) {
            log.warn("Tool-call verifier could not read the conversation tail; judging the message alone", e);
            return "";
        }
    }

    /**
     * The verifier errored or answered nothing. Which way that goes is {@code
     * app.guardrails.tool-verifier.fail-mode}, which by default is the global {@code
     * app.guardrails.fail-mode} (closed unless an operator opened it): closed refuses the call, because
     * a verifier that an attacker can make fail would otherwise be switched off by exactly that;
     * open approves it, so a verifier outage does not stop every RIGOROUS agent's tool calls.
     */
    private VerificationResult failedCheck(String toolName, String what, Exception cause) {
        if (GuardrailSettings.toolVerifierFailMode(tuning.getToolVerifier()) == GuardrailFailMode.CLOSED) {
            log.warn("Tool-call verifier {} for tool '{}' - refusing the call (fail-closed)", what, toolName, cause);
            return VerificationResult.rejected(tuning.getToolVerifier().getUnavailableReason());
        }
        log.warn("Tool-call verifier {} for tool '{}' - proceeding without verification this call (fail-open)",
            what, toolName, cause);
        return VerificationResult.approved("verifier unavailable - failed open");
    }
}
