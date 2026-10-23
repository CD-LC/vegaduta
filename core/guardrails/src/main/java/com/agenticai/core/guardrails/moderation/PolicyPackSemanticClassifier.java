package com.agenticai.core.guardrails.moderation;

import com.agenticai.core.guardrails.GuardrailPrompts;
import com.agenticai.core.guardrails.GuardrailSettings;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import com.agenticai.core.prompts.PromptResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Phase P6, Layer 2 (semantic). The optional LLM-judge half of a policy pack -
 * the same technique {@code SemanticGuardrailService} uses (one cheap,
 * non-streamed classification call against the turn's already-built {@link
 * ChatClient}), asked here to score content against ONE moderation category
 * and return a 0-100 confidence rather than a binary safe/unsafe.
 *
 * <p>Same fail-open posture as {@code SemanticGuardrailService#sanitizeToolOutput}:
 * a null {@link ChatClient}, a blank/garbled model response, or any exception
 * yields confidence {@code 0} (nothing fired), so a verifier outage degrades
 * to "pattern-only checking this call" - it never wrongly escalates or blocks
 * ordinary content because the model was unreachable. This is the safe
 * direction for an opt-in moderation gate.
 *
 * <p>A confidence score is only ever a SIGNAL - {@link ModerationService}
 * maps it against the pack's admin-configured review/block thresholds. The
 * middle band is what produces a human-review queue item rather than an
 * auto-decision, which is the entire point of the layered design.
 */
@Component
public class PolicyPackSemanticClassifier {

    private static final Logger log = LoggerFactory.getLogger(PolicyPackSemanticClassifier.class);

    /**
     * The judge window and sampling ({@code app.guardrails.judge.*}): an oversized payload could
     * otherwise push this call's own classification instructions out of the model's effective
     * attention window.
     */
    private final GuardrailTuningProperties tuning;

    /** Code defaults (no {@link GuardrailTuningProperties} bean). */
    public PolicyPackSemanticClassifier() {
        this(new GuardrailTuningProperties());
    }

    @Autowired
    public PolicyPackSemanticClassifier(GuardrailTuningProperties tuning) {
        this.tuning = tuning;
    }

    /**
     * Where the judge prompt ({@code judge.policy_pack}) and the category meanings ({@code
     * judge.policy_pack.<category>}) come from; the shipped texts unless the application wires the registry.
     */
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
    }

    /**
     * @param chatClient the turn's ChatClient - nullable; null means "no
     *     semantic layer available this call", returns 0.
     * @return 0-100 confidence that {@code content} violates {@code pack};
     *     0 on any failure (fail-open).
     */
    public int score(ChatClient chatClient, ModerationPolicyPack pack, String content) {
        if (chatClient == null || content == null || content.isBlank()) {
            return 0;
        }
        try {
            String system = GuardrailPrompts.scopedText(
                prompts, GuardrailPrompts.JUDGE_POLICY_PACK, "category", pack.configKey(), "description", describe(pack));
            String verdict = tuning.getJudge().apply(chatClient.prompt()
                .system(system)
                .user("Content to assess:\n" + GuardrailSettings.truncateForJudge(tuning, content)))
                .call()
                .content();
            return parseScore(verdict);
        } catch (Exception ex) {
            log.warn("Semantic moderation classifier failed for pack '{}' - treating as score 0 (fail-open)", pack.configKey(), ex);
            return 0;
        }
    }

    private int parseScore(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return 0;
        }
        // Pull the first integer out of the response; tolerant of "SCORE: 72",
        // "72", trailing punctuation, or a stray sentence the model added.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{1,3}").matcher(verdict);
        if (!m.find()) {
            return 0;
        }
        int score = Integer.parseInt(m.group());
        return Math.max(0, Math.min(100, score));
    }

    private String describe(ModerationPolicyPack pack) {
        return switch (pack) {
            case FOUL_LANGUAGE -> categoryText("foul_language");
            case RESTRICTED_CONTENT -> categoryText("restricted_content");
            case PII -> categoryText("pii");
            case HARASSMENT -> categoryText("harassment");
            case EXTREMISM -> categoryText("extremism");
            case SELF_HARM -> categoryText("self_harm");
            case ILLEGAL -> categoryText("illegal");
            // This branch exists ONLY to keep the switch total (it has no
            // default, deliberately, so a new pack is a compile error here
            // rather than a silent no-op). It is unreachable: CSAM's
            // requiresLegalReview() forces the pack out of enabledPacks()
            // before any layer runs. Do NOT treat this as a shipped CSAM
            // judge prompt - that category belongs to the external safety
            // classifier seam, designed with counsel, never an LLM judge.
            case CSAM -> "unreachable - legally gated pack, never enabled (see ModerationPolicyPack.CSAM)";
        };
    }

    /** What the category means, from {@code judge.policy_pack.<category>}. */
    private String categoryText(String category) {
        return GuardrailPrompts.scopedText(prompts, GuardrailPrompts.JUDGE_POLICY_PACK_CATEGORY_PREFIX + category);
    }
}
