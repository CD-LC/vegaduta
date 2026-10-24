package com.agenticai.core.common.diagnostics;

import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptSlot;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Produces the {@code TECHNICAL} and {@code EXPERT_TECHNICAL} tiers together, in one
 * {@link #explain(MaskedFailureContext)} call - see this package's design note for
 * why generating both from a single internal call (never two separate LLM calls) and
 * why this doesn't implement {@link ExplanationTierStrategy}.
 *
 * <p><b>Two-level lookup</b>:
 * <ol>
 *   <li>An in-code template map covering the failure shapes this codebase already has
 *       real, recurring precedent for ({@code GuardrailViolationException}, {@code
 *       UpstreamProviderException}, and a generic {@code Exception}/{@code
 *       RuntimeException} bucket) - deterministic, zero LLM cost, {@code
 *       llmGenerated=false}.</li>
 *   <li>Only when no entry matches (a genuinely novel/unmapped exception type) does
 *       this fall through to exactly ONE LLM call producing both tiers from a single
 *       prompt/response, over the already-masked context - the exact {@code
 *       SemanticGuardrailService} convention: an optional injected {@link
 *       ChatClient} (null-safe - no client means immediate fallback), a rigid
 *       two-line response contract ({@code TECHNICAL: ...} / {@code EXPERT: ...})
 *       parsed defensively, a {@code MAX_LLM_INPUT_CHARS} truncation cap mirroring
 *       {@code MAX_JUDGE_INPUT_CHARS}, and fail-open error handling - any exception,
 *       timeout, or blank/malformed response silently falls back to a generic
 *       templated explanation built from the masked exception class name + masked
 *       message, never throws, never leaves a caller with a missing tier.</li>
 * </ol>
 */
@Component
class LlmBackedTechnicalStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmBackedTechnicalStrategy.class);

    /**
     * Same rationale and same order of magnitude as {@code
     * SemanticGuardrailService#MAX_JUDGE_INPUT_CHARS}: caps how much of the masked
     * message is embedded into the LLM prompt, so an oversized failure message can't
     * blow the cost/latency budget of what is meant to be one cheap call per novel
     * failure type.
     */
    private static final int MAX_LLM_INPUT_CHARS = 4000;

    private static final String TECHNICAL_PREFIX = "TECHNICAL:";
    private static final String EXPERT_PREFIX = "EXPERT:";

    /** Registry key of the system prompt ({@code prompts/en/diagnostics.technical_explanation.md}). */
    static final String SYSTEM_PROMPT_KEY = "diagnostics.technical_explanation";

    private final PromptSlot prompts = new PromptSlot();

    /** The prompt registry, so an owner can reword the instruction; absent = the shipped text. */
    @Autowired(required = false)
    void setPromptResolver(PromptResolver resolver) {
        prompts.use(resolver);
    }

    private final Map<String, Function<MaskedFailureContext, TechnicalExplanation>> templates = Map.of(
        "GuardrailViolationException", LlmBackedTechnicalStrategy::guardrailTemplate,
        "UpstreamProviderException", LlmBackedTechnicalStrategy::upstreamProviderTemplate,
        "Exception", LlmBackedTechnicalStrategy::genericTemplate,
        "RuntimeException", LlmBackedTechnicalStrategy::genericTemplate
    );

    TechnicalExplanation explain(MaskedFailureContext context) {
        Function<MaskedFailureContext, TechnicalExplanation> template = templates.get(context.exceptionSimpleName());
        if (template != null) {
            return template.apply(context);
        }

        if (context.chatClient() != null) {
            TechnicalExplanation llmResult = tryLlm(context);
            if (llmResult != null) {
                return llmResult;
            }
        }

        return genericTemplate(context);
    }

    private TechnicalExplanation tryLlm(MaskedFailureContext context) {
        try {
            String verdict = context.chatClient().prompt()
                .system(prompts.text(SYSTEM_PROMPT_KEY))
                .user(buildUserPrompt(context))
                .call()
                .content();

            return parseLlmResponse(verdict);
        } catch (Exception ex) {
            // Fail open, deliberately - same posture as SemanticGuardrailService's own
            // fail-open handling: an LLM outage must degrade to a generic templated
            // explanation, never turn into a second visible failure on top of the
            // original one.
            log.warn("LLM-backed technical explanation failed for exception type '{}' - "
                + "falling back to a generic templated explanation", context.exceptionSimpleName(), ex);
            return null;
        }
    }

    private static TechnicalExplanation parseLlmResponse(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return null;
        }
        String[] lines = verdict.strip().split("\\r?\\n", 2);
        if (lines.length < 2) {
            return null;
        }
        String technicalLine = lines[0].trim();
        String expertLine = lines[1].trim();
        if (!technicalLine.regionMatches(true, 0, TECHNICAL_PREFIX, 0, TECHNICAL_PREFIX.length())
            || !expertLine.regionMatches(true, 0, EXPERT_PREFIX, 0, EXPERT_PREFIX.length())) {
            return null;
        }
        String technical = technicalLine.substring(TECHNICAL_PREFIX.length()).trim();
        String expert = expertLine.substring(EXPERT_PREFIX.length()).trim();
        if (!StringUtils.hasText(technical) || !StringUtils.hasText(expert)) {
            return null;
        }
        return new TechnicalExplanation(technical, expert, true);
    }

    private static String buildUserPrompt(MaskedFailureContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Exception type: ").append(context.exceptionClassName()).append('\n');
        if (StringUtils.hasText(context.maskedToolOrProviderName())) {
            prompt.append("Tool/provider: ").append(context.maskedToolOrProviderName()).append('\n');
        }
        if (StringUtils.hasText(context.issueSourceHint())) {
            prompt.append("Issue category: ").append(context.issueSourceHint()).append('\n');
        }
        prompt.append("Message: ").append(truncate(context.maskedMessage())).append('\n');
        context.maskedAdditionalContext().forEach((key, value) ->
            prompt.append(key).append(": ").append(truncate(value)).append('\n'));
        return prompt.toString();
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_LLM_INPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_LLM_INPUT_CHARS) + " …[truncated]";
    }

    private static TechnicalExplanation guardrailTemplate(MaskedFailureContext context) {
        String tool = StringUtils.hasText(context.maskedToolOrProviderName())
            ? context.maskedToolOrProviderName() : "an unspecified tool";
        return new TechnicalExplanation(
            "A guardrail hard-blocked output from tool '" + tool + "' before it reached the model - "
                + safeMessage(context) + ".",
            "GuardrailViolationException on tool '" + tool + "'. Reason: " + safeMessage(context)
                + ". Check GuardedToolCallback's pattern/semantic layer for a false-positive match, "
                + "or the tool's actual output for a real injection attempt.",
            false
        );
    }

    private static TechnicalExplanation upstreamProviderTemplate(MaskedFailureContext context) {
        String provider = StringUtils.hasText(context.maskedToolOrProviderName())
            ? context.maskedToolOrProviderName() : "an unspecified provider";
        return new TechnicalExplanation(
            "The upstream LLM provider '" + provider + "' rejected or failed the request - "
                + safeMessage(context) + ".",
            "UpstreamProviderException from provider '" + provider + "'. " + safeMessage(context)
                + ". Likely an auth/billing/rate-limit/model-availability issue on the provider "
                + "side - check that provider's credentials and quota.",
            false
        );
    }

    private static TechnicalExplanation genericTemplate(MaskedFailureContext context) {
        return new TechnicalExplanation(
            "An error of type " + context.exceptionSimpleName() + " occurred: " + safeMessage(context) + ".",
            context.exceptionClassName() + ": " + safeMessage(context)
                + (context.issueSourceHint() != null ? " (issue source: " + context.issueSourceHint() + ")" : ""),
            false
        );
    }

    private static String safeMessage(MaskedFailureContext context) {
        return StringUtils.hasText(context.maskedMessage()) ? context.maskedMessage() : "no further detail available";
    }

    /**
     * @param llmGenerated {@code true} only when {@code technical}/{@code expert} came
     *     from the LLM branch above rather than the template map.
     */
    record TechnicalExplanation(String technical, String expert, boolean llmGenerated) {
    }
}
