package com.agenticai.core.chat.gateway;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redacts (not merely flags) common PII patterns from outbound prompt text -
 * the gateway-layer counterpart to {@code core/guardrails}' {@code
 * PiiGuardrail}, which only EVALUATES content and returns pass/fail. That
 * distinction is deliberate here: {@link LlmGatewayContentPolicyAdvisor}
 * needs to keep the call going with the sensitive substring actually removed,
 * not reject a legitimate chat turn just because the user pasted their own
 * phone number.
 *
 * <p>Stateless utility, not a Spring bean - core/chat cannot depend on
 * core/guardrails (guardrails -&gt; approval -&gt; rl -&gt; chat is an
 * existing dependency chain; the reverse edge would cycle - verified against
 * every module's pom.xml before writing this), so detection patterns are
 * reimplemented independently here rather than shared. Where a pattern's
 * shape matches an existing one in this codebase (email/phone/credit-card,
 * {@code PiiGuardrail}) it is intentionally kept equivalent, including the
 * India-first phone grouping ({@code PiiGuardrail}'s own comment: "the
 * platform is India-first, so it must not be missed").
 *
 * <p>{@link LlmGatewayPiiCategory#GOVERNMENT_ID} is broader than {@code
 * PiiGuardrail}'s SSN-only pattern: US SSN ({@code XXX-XX-XXXX}), India
 * Aadhaar (12 digits, optionally space/hyphen grouped 4-4-4), and India PAN
 * (5 letters + 4 digits + 1 letter) - the three government-ID formats this
 * platform's own user base (India-first, per the phone-pattern precedent
 * above, with a US/global tenant base too) actually produces.
 *
 * <p>Redaction order matters for one reason only: CREDIT_CARD's 16-digit
 * pattern is checked before GOVERNMENT_ID's 12-digit Aadhaar pattern so a
 * card number can never be partially consumed by the narrower digit-count
 * match first (the two patterns cannot both match the same digit run, since
 * they require different lengths bounded by {@code (?<!\d)}/{@code (?!\d)},
 * but checking the more specific/longer pattern first is the safer default
 * if either regex is ever loosened later).
 */
final class LlmGatewayPiiRedactor {

    private LlmGatewayPiiRedactor() {
    }

    // Same shape as PiiGuardrail.EMAIL_PATTERN (core/guardrails) - kept
    // independent per this class's javadoc.
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    // Same shape as PiiGuardrail.PHONE_PATTERN - NANP + India 5-5 grouping.
    private static final Pattern PHONE_PATTERN = Pattern.compile(
        "(?<!\\d)(?:\\+?\\d{1,3}[-. ]?)?"
            + "(?:"
            + "(?:\\(\\d{3}\\)|\\d{3})[-. ]?\\d{3}[-. ]?\\d{4}"
            + "|\\d{5}[-. ]?\\d{5}"
            + ")(?!\\d)");

    // US Social Security Number.
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    // India Aadhaar - 12 digits, optionally grouped 4-4-4 with a space or hyphen.
    private static final Pattern AADHAAR_PATTERN =
        Pattern.compile("(?<!\\d)\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}(?!\\d)");

    // India PAN (Permanent Account Number) - 5 letters, 4 digits, 1 letter.
    private static final Pattern PAN_PATTERN = Pattern.compile("\\b[A-Za-z]{5}\\d{4}[A-Za-z]\\b");

    // Same shape as PiiGuardrail.CREDIT_CARD_PATTERN - 16 digits, optionally grouped in 4s.
    private static final Pattern CREDIT_CARD_PATTERN =
        Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b");

    private static final String EMAIL_PLACEHOLDER = "[REDACTED_EMAIL]";
    private static final String PHONE_PLACEHOLDER = "[REDACTED_PHONE]";
    private static final String GOVERNMENT_ID_PLACEHOLDER = "[REDACTED_GOVERNMENT_ID]";
    private static final String CREDIT_CARD_PLACEHOLDER = "[REDACTED_CARD]";

    /**
     * @return {@code text} unchanged when it's blank, {@code categories} is
     *     empty, or nothing in any enabled category matches - callers can use
     *     {@code result.equals(text)} (or reference equality is NOT
     *     guaranteed, unlike {@code SystemMessageMergingAdvisor}'s no-op
     *     case) to decide whether a message actually needs rebuilding.
     */
    static String redact(String text, Set<LlmGatewayPiiCategory> categories) {
        if (text == null || text.isBlank() || categories == null || categories.isEmpty()) {
            return text;
        }
        String result = text;
        // EMAIL first - its pattern (requires '@') cannot collide with any
        // digit-based pattern below, so order relative to them is irrelevant;
        // checked first purely so an email-looking token is never partially
        // eaten by a later digit-only pattern matching a numeric fragment
        // inside it (e.g. a domain that happens to contain a phone-shaped run).
        if (categories.contains(LlmGatewayPiiCategory.EMAIL)) {
            result = EMAIL_PATTERN.matcher(result).replaceAll(EMAIL_PLACEHOLDER);
        }
        if (categories.contains(LlmGatewayPiiCategory.CREDIT_CARD)) {
            result = CREDIT_CARD_PATTERN.matcher(result).replaceAll(CREDIT_CARD_PLACEHOLDER);
        }
        if (categories.contains(LlmGatewayPiiCategory.GOVERNMENT_ID)) {
            result = SSN_PATTERN.matcher(result).replaceAll(GOVERNMENT_ID_PLACEHOLDER);
            result = AADHAAR_PATTERN.matcher(result).replaceAll(GOVERNMENT_ID_PLACEHOLDER);
            result = PAN_PATTERN.matcher(result).replaceAll(GOVERNMENT_ID_PLACEHOLDER);
        }
        if (categories.contains(LlmGatewayPiiCategory.PHONE)) {
            result = PHONE_PATTERN.matcher(result).replaceAll(PHONE_PLACEHOLDER);
        }
        return result;
    }
}
