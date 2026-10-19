package com.agenticai.core.agents.tools;

import java.util.List;
import java.util.Map;

/**
 * Secret-scrubbing helpers extracted from {@link DynamicMcpConnectionManager} (behaviour unchanged;
 * the manager keeps package-private static delegates so existing callers/tests are unaffected).
 */
final class McpSecretRedactor {

    /**
     * Strips any occurrence of one of this registration's own configured secret values out of a
     * failure message before it's persisted/returned - see the call site in {@link #connect} for
     * why this is needed even though the secret genuinely belongs to the tenant who's about to read
     * this error (echoing a raw credential back into a field the rest of this codebase treats as
     * safe-to-display, e.g. into a browser's rendered DOM or a screen-share, is worth closing
     * regardless of who "owns" the value). Package-private, not private, specifically so this
     * pure/deterministic piece of the credential-isolation fix can be unit-tested directly without
     * standing up the full {@link #connect} flow's subprocess/SDK machinery (which has no test
     * coverage today for exactly that reason - see class javadoc's own honest caveat about the
     * unverified SDK constructor arity).
     */
    /**
     * Line-wise {@link #redactSecrets} for a stderr tail.
     *
     * <p>Exists because the self-healer takes the tail as a List and ships it to a model provider
     * as prompt text - see the call sites. Redacting each line separately rather than the joined
     * block is deliberate: a secret cannot span a line boundary, and keeping the list shape means
     * the healer prompt is byte-identical to what it was apart from the masking.
     */
    static List<String> redactSecretsIn(List<String> lines, Map<String, String> secretValues) {
        if (lines == null || secretValues == null || secretValues.isEmpty()) {
            return lines;
        }
        return lines.stream().map(line -> redactSecrets(line, secretValues)).toList();
    }

    static String redactSecrets(String message, Map<String, String> secretValues) {
        if (message == null || secretValues == null || secretValues.isEmpty()) {
            return message;
        }
        String redacted = message;
        for (String secret : secretValues.values()) {
            if (secret == null || secret.isBlank()) {
                continue;
            }
            redacted = replaceIgnoreCase(redacted, secret, "***");
            // L3 hardening (2026-08-14): a tenant-configured secret value that
            // was copy-pasted straight out of a URL can itself still be
            // percent-encoded (e.g. "a%20b%2Bc") while a failing tool's own
            // error text echoes back the RAW decoded credential ("a b+c") -
            // redact that decoded form too. Safe no-op for a secret that
            // isn't valid percent-encoding (IllegalArgumentException means
            // there's nothing additional to decode/redact).
            try {
                String urlDecoded =
                        java.net.URLDecoder.decode(secret, java.nio.charset.StandardCharsets.UTF_8);
                if (!urlDecoded.equals(secret) && !urlDecoded.isBlank()) {
                    redacted = replaceIgnoreCase(redacted, urlDecoded, "***");
                }
            } catch (IllegalArgumentException ignored) {
                // Not validly percent-encoded - nothing additional to redact.
            }
        }
        return redacted;
    }

    /**
     * Case-insensitive literal substring replace - deliberately NOT regex-based, so this can never
     * be handed attacker-influenced {@code haystack} content (a subprocess/exception-derived
     * message) and risk ReDoS; worst case is the same O(haystack x needle) scan {@link
     * String#replace} already had. Case-insensitivity can only ever redact MORE text that already
     * exactly matches a known secret value (mod case) - it can never produce a false positive on
     * content that isn't actually the secret, so there's no over-redaction risk from this alone.
     */
    private static String replaceIgnoreCase(String haystack, String needle, String replacement) {
        if (needle.isEmpty()) {
            return haystack;
        }
        StringBuilder out = new StringBuilder(haystack.length());
        String lowerHaystack = haystack.toLowerCase(java.util.Locale.ROOT);
        String lowerNeedle = needle.toLowerCase(java.util.Locale.ROOT);
        int i = 0;
        while (i < haystack.length()) {
            int idx = lowerHaystack.indexOf(lowerNeedle, i);
            if (idx < 0) {
                out.append(haystack, i, haystack.length());
                break;
            }
            out.append(haystack, i, idx).append(replacement);
            i = idx + needle.length();
        }
        return out.toString();
    }
}
