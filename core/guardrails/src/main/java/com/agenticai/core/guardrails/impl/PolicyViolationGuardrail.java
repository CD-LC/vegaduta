package com.agenticai.core.guardrails.impl;

import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailResult;
import com.agenticai.core.guardrails.ContentGuardrailType;
import java.util.regex.Pattern;
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

    // Violence-related patterns
    private static final Pattern VIOLENCE_PATTERN = Pattern.compile(
        "\\b(kill|murder|shoot|bomb|assault|attack|stab|rape|torture|execute)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Illegal activity patterns
    private static final Pattern ILLEGAL_ACTIVITY_PATTERN = Pattern.compile(
        "\\b(drug|cocaine|heroin|meth|counterfeit|steal|rob|hack|phishing|"
        + "cybercrime|embezzle|money.?launder|fraud|blackmail|extort)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Hate speech and discrimination patterns
    private static final Pattern HATE_SPEECH_PATTERN = Pattern.compile(
        "\\b(racist|sexist|homophobe|transphobe|ableist|"
        + "inferior.?(?:race|gender)|supremacist|inferior)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Self-harm and suicide patterns
    private static final Pattern SELF_HARM_PATTERN = Pattern.compile(
        "\\b(suicide|self.?harm|self.?injur|cut.?wrist|hang.?self|overdose.?intent)\\b",
        Pattern.CASE_INSENSITIVE
    );

    // Child safety patterns (absolute zero-tolerance)
    private static final Pattern CHILD_SAFETY_PATTERN = Pattern.compile(
        "\\b(child.?(?:abuse|exploitation|grooming|trafficking)|"
        + "pedophil|child.?pornograph|csam|minors?.?(?:exploit|abuse))\\b",
        Pattern.CASE_INSENSITIVE
    );

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
        if (CHILD_SAFETY_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                "Content violates critical safety policies regarding child protection. This request cannot be fulfilled.",
                100
            );
        }

        // Self-harm detection (high priority)
        if (SELF_HARM_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                "Content related to self-harm is not permitted. If you're struggling, please reach out to a mental health professional.",
                95
            );
        }

        // Violence detection
        if (VIOLENCE_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                "Content promoting violence is not permitted on this platform.",
                85
            );
        }

        // Illegal activity detection
        if (ILLEGAL_ACTIVITY_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                "Content promoting illegal activities is not permitted.",
                80
            );
        }

        // Hate speech detection
        if (HATE_SPEECH_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                ContentGuardrailType.POLICY_VIOLATION,
                "Content containing hate speech or discrimination is not permitted.",
                80
            );
        }

        return ContentGuardrailResult.pass(ContentGuardrailType.POLICY_VIOLATION);
    }
}
