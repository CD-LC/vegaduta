package com.agenticai.core.guardrails.impl;

import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailResult;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.guardrails.GuardrailPrompts;
import com.agenticai.core.guardrails.PatternSets;
import com.agenticai.core.prompts.PromptResolver;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Detects content that violates platform policies: violence, illegal activities,
 * hate speech, discrimination, and other prohibited content categories.
 *
 * <p>Uses pattern matching for common policy-violating keywords and concepts.
 * This is a starting point; production deployments should integrate with
 * more sophisticated classifiers.
 *
 * <p><b>Premium+ feature.</b> Enforcement of platform content policies.
 */
@Component
public class PolicyViolationGuardrail implements ContentGuardrail {

    // The terms are guardrails/patterns/<locale>/policy-*.txt (every locale on the classpath, English first):
    // the English files hold exactly the patterns this class compiled before they moved.
    private static final List<Pattern> VIOLENCE_PATTERNS = patterns("policy-violence");
    private static final List<Pattern> ILLEGAL_ACTIVITY_PATTERNS = patterns("policy-illegal-activity");
    private static final List<Pattern> HATE_SPEECH_PATTERNS = patterns("policy-hate-speech");
    private static final List<Pattern> SELF_HARM_PATTERNS = patterns("policy-self-harm");
    // Child safety is absolute zero-tolerance and is checked first.
    private static final List<Pattern> CHILD_SAFETY_PATTERNS = patterns("policy-child-safety");

    private static List<Pattern> patterns(String set) {
        return PatternSets.localized(set).stream().map(PatternSets.Entry::pattern).toList();
    }

    private static boolean matches(List<Pattern> patterns, String content) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }

    /** Where the refusal texts come from ({@code refusal.guardrail.*}); the shipped texts unless the application wires the registry. */
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
    }

    private String refusal(String key) {
        return GuardrailPrompts.scopedText(prompts, key);
    }

    @Override
    public ContentGuardrailType getType() {
        return ContentGuardrailType.POLICY_VIOLATION;
    }

    @Override
    public ContentGuardrailResult evaluate(String content) {
        if (content == null || content.isBlank()) {
            return ContentGuardrailResult.pass(ContentGuardrailType.POLICY_VIOLATION);
        }

        // Highest priority: child safety (zero tolerance)
        if (matches(CHILD_SAFETY_PATTERNS, content)) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                refusal(GuardrailPrompts.REFUSAL_CHILD_SAFETY),
                100
            );
        }

        // Self-harm detection (high priority)
        if (matches(SELF_HARM_PATTERNS, content)) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                refusal(GuardrailPrompts.REFUSAL_SELF_HARM),
                95
            );
        }

        // Violence detection
        if (matches(VIOLENCE_PATTERNS, content)) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                refusal(GuardrailPrompts.REFUSAL_VIOLENCE),
                85
            );
        }

        // Illegal activity detection
        if (matches(ILLEGAL_ACTIVITY_PATTERNS, content)) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                refusal(GuardrailPrompts.REFUSAL_ILLEGAL_ACTIVITY),
                80
            );
        }

        // Hate speech detection
        if (matches(HATE_SPEECH_PATTERNS, content)) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                refusal(GuardrailPrompts.REFUSAL_HATE_SPEECH),
                80
            );
        }

        return ContentGuardrailResult.pass(ContentGuardrailType.POLICY_VIOLATION);
    }
}
