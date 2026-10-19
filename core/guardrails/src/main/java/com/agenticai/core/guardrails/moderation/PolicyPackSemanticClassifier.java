package com.agenticai.core.guardrails.moderation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
     * Cap on how much of the content is embedded into the judge prompt. Same
     * rationale and value as {@code ToxicityGuardrail}'s cap - an oversized
     * payload could otherwise push this call's own classification
     * instructions out of the model's effective attention window.
     */
    private static final int MAX_JUDGE_INPUT_CHARS = 4000;

    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are an independent content-safety classifier. You are given a piece of content and ONE
        category to assess. Decide how confident you are that the content violates that specific
        category, on a 0-100 scale, where 0 = definitely does not, 100 = definitely does.

        Category: %s
        What this category means: %s

        Judge ONLY this category. Do not flag content that merely mentions or discusses the topic in a
        neutral, educational, journalistic, or clearly fictional way - only content that actually
        exhibits the harmful category itself.

        Respond with EXACTLY one line in this format, no other text:
        SCORE: <integer 0-100>
        """;

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
            String system = String.format(SYSTEM_PROMPT_TEMPLATE, pack.configKey(), describe(pack));
            String verdict = chatClient.prompt()
                .system(system)
                .user("Content to assess:\n" + truncateForJudge(content))
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

    private static String truncateForJudge(String text) {
        if (text.length() <= MAX_JUDGE_INPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_JUDGE_INPUT_CHARS) + " …[truncated]";
    }

    private String describe(ModerationPolicyPack pack) {
        return switch (pack) {
            case FOUL_LANGUAGE -> "profanity, obscene or vulgar language";
            case RESTRICTED_CONTENT -> "content a business would restrict for brand-safety reasons, such as explicit adult content, gambling promotion, or weapons sales";
            case PII -> "personally identifiable information such as real names tied to contact details, emails, phone numbers, government IDs, or payment card numbers";
            case HARASSMENT -> "harassment, bullying, threats, or targeted abuse of a person";
            case EXTREMISM -> "hate speech or violent-extremist rhetoric targeting people by a protected characteristic (this is about rhetoric, not a specific credible terrorism threat)";
            case SELF_HARM -> "content expressing intent to self-harm or suicide, or encouraging others to do so";
            case ILLEGAL -> "solicitation or facilitation of clearly illegal activity - judge intent, not topic; reporting, research and fiction must not score";
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
}
