package com.agenticai.core.common.diagnostics;

import com.agenticai.core.security.TextRedaction;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Diagnostics build-out (2026-08) - deterministic, regex-based redaction of anything secret- or
 * PII-shaped in a piece of diagnostic text (an exception message, a log line snippet, a config-key
 * value) before it is ever handed to any tier-generation strategy in this package, including {@code
 * EXPERT_TECHNICAL} - see {@link DefaultFailureExplanationService} for the single call site that
 * guarantees this.
 *
 * <p>Deliberately a plain, Spring-free POJO with one public method and zero constructor
 * dependencies (mirroring how {@link com.agenticai.core.guardrails.SemanticGuardrailService}
 * instantiates {@code PatternBasedGuardrailService} with a plain {@code new} rather than DI) - this
 * needs to be trivially unit-testable with plain string fixtures and no Spring context, and needs
 * to be usable standing completely alone if some other module ever wants the same masking without
 * pulling in this whole diagnostics package. It is still annotated {@link Component} so {@link
 * DefaultFailureExplanationService} can receive it via ordinary constructor injection - both usage
 * styles are supported.
 *
 * <p><b>Relationship to {@code PatternBasedGuardrailService}</b>: this class COPIES (does not
 * import or extend) that class's {@code REDACTION_PATTERNS} list verbatim (AWS key, Google API key,
 * GitHub token, Slack token, OpenAI-style key, PEM private key, JWT) so the two pattern sets can't
 * silently drift apart, rather than reusing that bean directly. Reusing it directly wasn't viable:
 * it is {@code @ConditionalOnProperty}-gated to one of two mutually-exclusive tool-output-
 * sanitization implementations (see that class's own javadoc), and its pattern set is scoped
 * narrowly to prompt-injection/credential-in-tool-output defense - this class needs a broader,
 * diagnostic-specific PII set (emails, phone numbers, IP addresses, connection-string credentials,
 * generic {@code key=value} secrets, filesystem paths, credit-card-shaped digit runs) that
 * tool-output sanitization never needed.
 *
 * <p><b>Pipeline, always run in this fixed order</b> (see {@link #mask(String)}):
 *
 * <ol>
 *   <li>Structural pass - NOT regex-secret-detection, just text hygiene run first so later passes
 *       never see a raw filesystem path: stack-trace frames are rewritten to keep only {@code
 *       Class.method(File:line)} (stripping any leading filesystem path inside the parens), then
 *       any remaining Windows ({@code C:\...}) or Unix ({@code
 *       /home|/Users|/opt|/var|/etc|/tmp/...}) absolute path anywhere else in the text is replaced
 *       with {@code [PATH]}.
 *   <li>Regex redaction pass, in this fixed order: the 7 copied guardrail patterns, then generic
 *       {@code key=value} secrets, connection-string embedded credentials, email addresses, phone
 *       numbers, IPv4/IPv6 addresses, and finally long digit runs shaped like a credit-card number
 *       (defense in depth).
 * </ol>
 *
 * <p>Every replacement is unconditional - there is no allowlist (e.g. for a known-public IP)
 * implemented here. That is a documented future extension point, not a gap: a diagnostic
 * explanation never strictly needs a raw IP to be useful.
 */
@Component
public class SensitiveDataMasker {

    // --- Structural pass -----------------------------------------------------------

    // "(<path><sep><File.ext>:<line>)" -> "(<File.ext>:<line>)". Greedy [^()]*[/\\]
    // naturally consumes up to the LAST slash before the filename, so nested
    // directories collapse correctly in one pass.
    private static final Pattern STACK_FRAME_PATH =
            Pattern.compile("\\(([^()]*[/\\\\])([^()/\\\\]+:\\d+)\\)");

    private static final Pattern WINDOWS_ABSOLUTE_PATH =
            Pattern.compile("[A-Za-z]:\\\\[^\\s\"'<>|]*");

    private static final Pattern UNIX_ABSOLUTE_PATH =
            Pattern.compile("/(?:home|Users|opt|var|etc|tmp)/[^\\s\"'<>]*");

    // --- Regex redaction pass --------------------------------------------------------

    // Copied verbatim from PatternBasedGuardrailService.REDACTION_PATTERNS - see this
    // class's javadoc for why this is a deliberate copy, not a shared reference. The PEM
    // private-key entry is applied first, in code (TextRedaction), not as a regular
    // expression: the lazy BEGIN...END pattern is quadratic on text full of BEGIN lines.
    private static final List<Pattern> COPIED_GUARDRAIL_SECRET_PATTERNS =
            List.of(
                    Pattern.compile("AKIA[0-9A-Z]{16}"),
                    Pattern.compile("AIza[0-9A-Za-z\\-_]{35}"),
                    Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}"),
                    Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
                    Pattern.compile("sk-[A-Za-z0-9]{20,}"),
                    Pattern.compile(
                            "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));

    // Generic "password=...", "api_key: ...", "token=\"...\"" style secrets that the
    // guardrail patterns above never needed to catch (they only ever looked at
    // fixed-shape provider key formats).
    private static final Pattern KEY_VALUE_SECRET =
            Pattern.compile(
                    "(?i)\\b(password|passwd|pwd|secret|api[_-]?key|access[_-]?key|token|credential|auth)\\b"
                            + "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;]+)");

    // scheme://user:pass@host - mask only the credential segment, keep scheme + host.
    private static final Pattern CONNECTION_STRING_CREDENTIALS =
            Pattern.compile(
                    "(?i)\\b((?:jdbc:)?(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis|amqp)://)"
                            + "([^:/@\\s]+):([^@\\s]+)@");

    // Optional country code / area code, then a 3-3-4 (or plain 10-digit) grouping -
    // deliberately NOT a bare "any long digit run" so common date shapes
    // (e.g. 2026-08-05, 4-2-2 grouping) don't false-positive as phone numbers.
    private static final Pattern PHONE =
            Pattern.compile(
                    "(?<!\\d)(?:\\+\\d{1,3}[-.\\s]?)?(?:\\(\\d{2,4}\\)[-.\\s]?)?\\d{3}[-.\\s]?\\d{3}[-.\\s]?\\d{4}(?!\\d)");

    private static final Pattern IPV4 =
            Pattern.compile(
                    "\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");

    // Best-effort, not a full RFC 5952 parser - covers full-form and "::"-compressed
    // addresses well enough for a diagnostic-text masker. See class javadoc: an
    // allowlist for known-safe IPs is a documented future extension, not built now.
    private static final Pattern IPV6 =
            Pattern.compile(
                    "\\b(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}\\b"
                            + "|\\b(?:[0-9A-Fa-f]{1,4}:){1,6}:(?:[0-9A-Fa-f]{1,4}:){0,5}[0-9A-Fa-f]{0,4}\\b"
                            + "|::(?:[0-9A-Fa-f]{1,4}:){0,6}[0-9A-Fa-f]{1,4}\\b");

    // 13-19 digits, optionally separated by single spaces/dashes between digits -
    // defense in depth for anything the more specific patterns above didn't catch.
    private static final Pattern CREDIT_CARD_LIKE = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");

    /**
     * Masks every secret-/PII-shaped substring in {@code rawText} and returns the result. {@code
     * null}/empty input passes through unchanged, mirroring {@code
     * PatternBasedGuardrailService#sanitizeToolOutput}'s same-shaped guard.
     */
    public String mask(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return rawText;
        }

        String text = rawText;

        // 1. Structural pass - always first, so nothing downstream ever sees a raw
        //    filesystem path.
        text = STACK_FRAME_PATH.matcher(text).replaceAll("($2)");
        text = WINDOWS_ABSOLUTE_PATH.matcher(text).replaceAll("[PATH]");
        text = UNIX_ABSOLUTE_PATH.matcher(text).replaceAll("[PATH]");

        // 2. Regex redaction pass, fixed order.
        text = TextRedaction.redactPrivateKeyBlocks(text, "[REDACTED]");
        for (Pattern secretPattern : COPIED_GUARDRAIL_SECRET_PATTERNS) {
            text = secretPattern.matcher(text).replaceAll("[REDACTED]");
        }
        text = maskKeyValueSecrets(text);
        text = CONNECTION_STRING_CREDENTIALS.matcher(text).replaceAll("$1[REDACTED]@");
        text = TextRedaction.redactEmails(text, "[EMAIL]");
        text = PHONE.matcher(text).replaceAll("[PHONE]");
        text = IPV4.matcher(text).replaceAll("[IP]");
        text = IPV6.matcher(text).replaceAll("[IP]");
        text = CREDIT_CARD_LIKE.matcher(text).replaceAll("[REDACTED]");

        return text;
    }

    // Keeps the key name (e.g. "password", "api_key") visible - only the value is
    // redacted - so the masked text still tells a reader WHAT kind of secret was
    // present, which matters for the technical/expert-technical tiers.
    private static String maskKeyValueSecrets(String text) {
        Matcher matcher = KEY_VALUE_SECRET.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement =
                    Matcher.quoteReplacement(matcher.group(1) + matcher.group(2) + "[REDACTED]");
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
