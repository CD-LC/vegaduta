package com.agenticai.core.guardrails.impl;

import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailResult;
import com.agenticai.core.guardrails.ContentGuardrailType;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Basic profanity filter using a curated keyword list and regex patterns.
 * Detects common offensive language and replaces it with `***` placeholders
 * in output context (though full content is blocked if triggered).
 *
 * <p>This is a heuristic-based approach suitable for quick filtering. For
 * more sophisticated language analysis, consider adding semantic checks later.
 */
@Component
public class ProfanityGuardrail implements ContentGuardrail {

    // Profanity patterns - deliberately kept modest for this example.
    // A production system would use a more comprehensive library or service.
    private static final Set<String> PROFANITY_KEYWORDS = new HashSet<>();
    private static final Pattern PROFANITY_PATTERN;

    static {
        // Example keywords - replace with a real profanity list in production.
        // These are example placeholders.
        PROFANITY_KEYWORDS.addAll(Set.of(
            "\\b(damn|hell|piss)\\b",
            "\\b(crap|sucks)\\b"
        ));

        // Build regex pattern: case-insensitive, word-boundary anchored
        String pattern = String.join("|", PROFANITY_KEYWORDS);
        PROFANITY_PATTERN = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    }

    @Override
    public ContentGuardrailType getType() {
        return ContentGuardrailType.PROFANITY;
    }

    @Override
    public ContentGuardrailResult evaluate(String content) {
        if (content == null || content.isBlank()) {
            return ContentGuardrailResult.pass(ContentGuardrailType.PROFANITY);
        }

        boolean hasProfanity = PROFANITY_PATTERN.matcher(content).find();
        if (hasProfanity) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.PROFANITY,
                "Content contains profanity. Please rephrase your request without offensive language.",
                85
            );
        }

        return ContentGuardrailResult.pass(ContentGuardrailType.PROFANITY);
    }
}
