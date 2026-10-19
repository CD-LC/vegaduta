package com.agenticai.core.guardrails;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Orchestrates content guardrails for both input (user message) and output
 * (agent response) paths.
 *
 * <p>Evaluates content through a set of configured guardrails and returns
 * a combined result indicating whether the content should be blocked or
 * allowed to proceed.
 *
 * <p><b>Contract:</b>
 * <ul>
 *   <li>Guardrails are evaluated in priority order (highest confidence first)</li>
 *   <li>First BLOCK short-circuits evaluation (fail-fast)</li>
 *   <li>All evaluations are logged for observability</li>
 *   <li>Falls back gracefully if guardrails are unavailable</li>
 * </ul>
 */
@Service
public class ContentGuardrailEngine {

    private static final Logger log = LoggerFactory.getLogger(ContentGuardrailEngine.class);

    private final Map<ContentGuardrailType, ContentGuardrail> guardrails;

    /**
     * Spring constructor - auto-wires all available {@link ContentGuardrail}
     * beans into a registry keyed by their type.
     */
    public ContentGuardrailEngine(ObjectProvider<List<ContentGuardrail>> guardrailsProvider) {
        this.guardrails = new HashMap<>();

        List<ContentGuardrail> allGuardrails = guardrailsProvider.getIfAvailable(ArrayList::new);
        for (ContentGuardrail guardrail : allGuardrails) {
            guardrails.put(guardrail.getType(), guardrail);
        }

        log.info("Initialized ContentGuardrailEngine with {} guardrails: {}",
            guardrails.size(), guardrails.keySet());
    }

    /**
     * Evaluates content through a set of enabled guardrails and returns a
     * combined result.
     *
     * @param content the text to evaluate
     * @param enabledGuardrails set of guardrail types to apply
     * @param context logging context (agent id, turn id, etc.)
     * @return result indicating pass/fail and reason
     */
    public ContentGuardrailCheckResult evaluate(
        String content,
        Set<ContentGuardrailType> enabledGuardrails,
        GuardrailEvaluationContext context
    ) {
        return evaluate(content, enabledGuardrails, context, null);
    }

    /**
     * Evaluates content through a set of enabled guardrails with optional
     * semantic analysis support.
     *
     * @param content the text to evaluate
     * @param enabledGuardrails set of guardrail types to apply
     * @param context logging context (agent id, turn id, etc.)
     * @param chatClient optional ChatClient for semantic guardrails
     * @return result indicating pass/fail and reason
     */
    public ContentGuardrailCheckResult evaluate(
        String content,
        Set<ContentGuardrailType> enabledGuardrails,
        GuardrailEvaluationContext context,
        ChatClient chatClient
    ) {
        if (content == null || content.isBlank()) {
            return ContentGuardrailCheckResult.pass();
        }

        if (enabledGuardrails == null || enabledGuardrails.isEmpty()) {
            return ContentGuardrailCheckResult.pass();
        }

        List<ContentGuardrailResult> results = new ArrayList<>();
        ContentGuardrailResult blockedBy = null;
        // The text the REMAINING guardrails see. Starts as the caller's
        // content and is replaced whenever a guardrail redacts in place
        // (ContentGuardrailResult#redact) - so a PII-style rewrite is visible
        // to every later check rather than each one re-scanning the raw text,
        // and the caller gets one fully-redacted string back at the end.
        String working = content;
        GuardrailEvaluationPath path = context != null ? context.path() : null;

        // Evaluate each enabled guardrail
        for (ContentGuardrailType type : enabledGuardrails) {
            ContentGuardrail guardrail = guardrails.get(type);
            if (guardrail == null) {
                log.warn("Guardrail not available: {} (check if bean is wired)", type);
                continue;
            }

            // 3-arg form always: a guardrail that does not care about the
            // direction inherits the default that drops it, and one that does
            // (the tenant-rule CUSTOM guardrail, which may only redact on the
            // output path) finally has a way to find out. chatClient is passed
            // as null to guardrails that declared they do not need one, which
            // is exactly what the 2-arg call did before.
            ContentGuardrailResult result = guardrail.evaluate(
                working, guardrail.requiresChatClient() ? chatClient : null, path);

            results.add(result);

            if (result.isPassed() && result.getRedactedContent() != null) {
                log.info("Guardrail {} redacted content for agent {}", type,
                    context != null ? context.agentId() : "unknown");
                working = result.getRedactedContent();
            }

            // Log the result
            if (!result.isPassed()) {
                log.warn(
                    "Guardrail violation: type={}, agent={}, confidence={}, reason={}",
                    type, context != null ? context.agentId() : "unknown",
                    result.getConfidence(), result.getReason()
                );
            }

            // Short-circuit on first block (fail-fast)
            if (!result.isPassed() && blockedBy == null) {
                blockedBy = result;
            }
        }

        // If any guardrail blocked, return failure with reason
        if (blockedBy != null) {
            return ContentGuardrailCheckResult.blocked(
                blockedBy.getGuardrailType(),
                blockedBy.getReason(),
                blockedBy.getConfidence()
            );
        }

        return ContentGuardrailCheckResult.pass(working);
    }

    /**
     * Returns the guardrail of a given type, if available.
     */
    public ContentGuardrail getGuardrail(ContentGuardrailType type) {
        return guardrails.get(type);
    }

    /**
     * Returns all registered guardrail types.
     */
    public Set<ContentGuardrailType> getAvailableGuardrails() {
        return guardrails.keySet();
    }

    /**
     * Context for guardrail evaluation - helps with logging and debugging.
     */
    public record GuardrailEvaluationContext(
        UUID agentId,
        String sessionId,
        String turnId,
        GuardrailEvaluationPath path
    ) {
        /**
         * Creates context for evaluating input (user message).
         */
        public static GuardrailEvaluationContext input(UUID agentId, String sessionId, String turnId) {
            return new GuardrailEvaluationContext(agentId, sessionId, turnId, GuardrailEvaluationPath.INPUT);
        }

        /**
         * Creates context for evaluating output (agent response).
         */
        public static GuardrailEvaluationContext output(UUID agentId, String sessionId, String turnId) {
            return new GuardrailEvaluationContext(agentId, sessionId, turnId, GuardrailEvaluationPath.OUTPUT);
        }

        /**
         * Creates context for evaluating an agent response that has ALREADY
         * been delivered - the token-streaming FAST depth, where the check runs
         * in the stream-completion callback purely to audit
         * ({@code AgentChatService#auditStreamedOutboundViolation}).
         *
         * <p>Distinct from {@link #output} on purpose: nothing this evaluation
         * returns can change what the reader saw, so a guardrail that would
         * otherwise rewrite the text must not bother. Without this distinction
         * a tenant REDACT rule would run a regex over every streamed answer,
         * log that it redacted something, and have the result thrown away.
         */
        public static GuardrailEvaluationContext outputAlreadyDelivered(UUID agentId, String sessionId, String turnId) {
            return new GuardrailEvaluationContext(
                agentId, sessionId, turnId, GuardrailEvaluationPath.OUTPUT_ALREADY_DELIVERED);
        }
    }

    /**
     * Path of evaluation - helps distinguish between input and output filtering.
     */
    public enum GuardrailEvaluationPath {
        INPUT("User Input"),
        OUTPUT("Agent Output"),
        /** The FAST depth's post-stream audit pass - detection only, the text is already on the reader's screen. */
        OUTPUT_ALREADY_DELIVERED("Agent Output (already delivered)");

        private final String displayName;

        GuardrailEvaluationPath(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}
