package com.agenticai.core.guardrails.impl;

import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailResult;
import com.agenticai.core.guardrails.ContentGuardrailType;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Detects personally identifiable information (PII) in content, primarily in agent responses to
 * prevent accidental data leakage.
 *
 * <p>Detects:
 *
 * <ul>
 *   <li>Email addresses (basic)
 *   <li>Phone numbers (various formats)
 *   <li>Social Security Numbers (XXX-XX-XXXX)
 *   <li>Credit card numbers (basic patterns)
 *   <li>Bank routing numbers
 *   <li>Common PII-related keywords in context
 * </ul>
 *
 * <p><b>Premium+ feature.</b> Prevents unintended disclosure of sensitive customer or business data
 * in agent responses.
 */
@Component
public class PiiGuardrail implements ContentGuardrail {

    // Email pattern (simplified - not RFC-complete but good enough for filtering)
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("\\b[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");

    // Phone numbers.
    //
    // The previous pattern claimed to support "(123) 456-7890" but could never match it,
    // for two independent reasons: it allowed only [-.] (never a SPACE) between groups,
    // and its leading \b could not hold between a space and the opening '(' (neither is a
    // word character). Its own unit test asserted the documented behaviour and failed.
    // That mattered little while this guardrail was unreachable; it matters now that the
    // engine is wired into the live request path.
    //
    // Covered formats:
    //   NANP      (555) 123-4567 | 555-123-4567 | 555.123.4567 | 5551234567 | +1 555 123 4567
    //   India     +91 98765 43210 | 98765 43210 | 9876543210   (5+5 grouping is the local
    //             convention and the platform is India-first, so it must not be missed)
    //
    // (?<!\d) / (?!\d) are used instead of \b so a match cannot start or end mid-number
    // (e.g. inside a 16-digit card number), which \b would happily allow.
    private static final Pattern PHONE_PATTERN =
            Pattern.compile(
                    "(?<!\\d)(?:\\+?\\d{1,3}[-. ]?)?" // optional country code (+1, +91)
                            + "(?:"
                            + "(?:\\(\\d{3}\\)|\\d{3})[-. ]?\\d{3}[-. ]?\\d{4}" // 3-3-4, parens
                            // optional
                            + "|\\d{5}[-. ]?\\d{5}" // 5-5 (India mobile)
                            + ")(?!\\d)");

    // Social Security Number (XXX-XX-XXXX)
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    // Credit Card (simplified - looks for common credit card format)
    private static final Pattern CREDIT_CARD_PATTERN =
            Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b");

    // Bank routing number (9 digits)
    private static final Pattern ROUTING_NUMBER_PATTERN = Pattern.compile("\\b\\d{9}\\b");

    // Common PII keywords that might appear with PII
    private static final Pattern PII_KEYWORD_PATTERN =
            Pattern.compile(
                    "(?i)\\b(ssn|social.?security|credit.?card|account.?number|routing|cvv|pin|password|secret)\\b");

    @Override
    public ContentGuardrailType getType() {
        return ContentGuardrailType.PII;
    }

    @Override
    public ContentGuardrailResult evaluate(String content) {
        if (content == null || content.isBlank()) {
            return ContentGuardrailResult.pass(ContentGuardrailType.PII);
        }

        // Check for email addresses
        if (EMAIL_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                    ContentGuardrailType.PII,
                    "Response contains email addresses. Please remove personally identifiable"
                            + " information.",
                    80);
        }

        // Check for phone numbers
        if (PHONE_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                    ContentGuardrailType.PII,
                    "Response contains phone numbers. Please remove personally identifiable"
                            + " information.",
                    85);
        }

        // Check for SSN
        if (SSN_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                    ContentGuardrailType.PII,
                    "Response contains social security numbers. This data must not be exposed.",
                    95);
        }

        // Check for credit card numbers
        if (CREDIT_CARD_PATTERN.matcher(content).find()) {
            return ContentGuardrailResult.fail(
                    ContentGuardrailType.PII,
                    "Response contains credit card numbers. This data must not be exposed.",
                    95);
        }

        // Check for PII keywords with numbers (stronger signal)
        if (PII_KEYWORD_PATTERN.matcher(content).find() && containsSuspiciousNumbers(content)) {
            return ContentGuardrailResult.fail(
                    ContentGuardrailType.PII,
                    "Response appears to contain sensitive authentication or account data.",
                    70);
        }

        return ContentGuardrailResult.pass(ContentGuardrailType.PII);
    }

    private boolean containsSuspiciousNumbers(String content) {
        // Look for sequences of numbers that might be account/card/routing numbers
        Pattern numberSequence = Pattern.compile("\\d{8,}");
        return numberSequence.matcher(content).find();
    }
}
