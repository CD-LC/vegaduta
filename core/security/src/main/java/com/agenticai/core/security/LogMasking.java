package com.agenticai.core.security;

/**
 * Masks personal data before it reaches a log line (JAVA-033). Logs are
 * shipped, retained and read far more widely than the database, so an email
 * address, IMAP login or phone number in an INFO/WARN line is PII leaving its
 * access controls. Keep enough to correlate ("which mailbox?") without the
 * identifier itself.
 */
public final class LogMasking {

    private LogMasking() {
    }

    /**
     * {@code jane.doe@example.com} -> {@code j***@example.com}; non-email
     * values fall back to {@link #lastFour}. {@code null} -> {@code "null"}.
     */
    public static String email(String address) {
        if (address == null) {
            return "null";
        }
        String trimmed = address.trim();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return lastFour(trimmed);
        }
        return trimmed.charAt(0) + "***" + trimmed.substring(at);
    }

    /** Last four characters only ({@code ***1234}); short values are fully masked. */
    public static String lastFour(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() < 8) {
            return "***";
        }
        return "***" + value.substring(value.length() - 4);
    }

    /** A body's size instead of its content: {@code <1234 chars>}. */
    public static String size(String body) {
        return body == null ? "<null>" : "<" + body.length() + " chars>";
    }

    private static final java.util.regex.Pattern SECRET_QUERY_PARAM = java.util.regex.Pattern.compile(
        "(?i)([?&;](?:key|api[-_]?key|apikey|access[-_]?token|token|secret|password|signature)=)[^&\\s\"'#]+");
    private static final java.util.regex.Pattern BEARER_OR_BASIC = java.util.regex.Pattern.compile(
        "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=\\-]{8,}");
    private static final java.util.regex.Pattern AUTH_HEADER_VALUE = java.util.regex.Pattern.compile(
        "(?i)((?:x-api-key|x-goog-api-key|api-key|authorization)\\s*[:=]\\s*)[^\\s,;\"']+");
    /** Vendor key shapes: OpenAI/Anthropic/OpenRouter sk-, Google AIza, Groq gsk_, Slack xox*, GitHub gh*_, HuggingFace hf_. */
    private static final java.util.regex.Pattern KNOWN_KEY_SHAPES = java.util.regex.Pattern.compile(
        "\\b(?:sk-[A-Za-z0-9_\\-]{16,}|AIza[0-9A-Za-z_\\-]{30,}|gsk_[A-Za-z0-9]{20,}|xox[abprs]-[A-Za-z0-9\\-]{10,}"
            + "|gh[pousr]_[A-Za-z0-9]{30,}|hf_[A-Za-z0-9]{30,})");

    /**
     * Scrubs credentials out of free text that is about to be logged or put in an exception
     * message: secret-looking query parameters ({@code ?key=...}), {@code Bearer}/{@code Basic}
     * credentials, key-bearing header values, and the common provider key shapes. Defence in depth
     * for the paths that cannot avoid handling a URL or an upstream error message with a key in it
     * (a JDK {@code URI} syntax error quotes the whole string, key included). It is not a
     * substitute for never putting a key in a URL. {@code null} stays {@code null}.
     */
    public static String secrets(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = SECRET_QUERY_PARAM.matcher(text).replaceAll("$1***");
        out = BEARER_OR_BASIC.matcher(out).replaceAll("$1 ***");
        out = AUTH_HEADER_VALUE.matcher(out).replaceAll("$1***");
        return KNOWN_KEY_SHAPES.matcher(out).replaceAll("***");
    }

    /**
     * SEC-050: path prefixes whose NEXT segment is a bearer capability token
     * (possession of the URL is the credential). {@code /webhook/workflows/oidc/..}
     * carries a workflow id, not a token, and is left readable.
     */
    private static final String[] TOKEN_PATH_PREFIXES = {
        "/webhook/workflows/",
        "/api/share/agents/",
        "/api/share/workflows/",
        "/api/demo/share/",
    };

    /**
     * Masks capability tokens embedded in a request path before it is logged
     * or audited: {@code /webhook/workflows/abcdef0123456789} ->
     * {@code /webhook/workflows/***6789}. Query strings are dropped entirely
     * (they can carry OAuth state, share tokens, signatures). Other paths are
     * returned unchanged. {@code null} -> {@code "null"}.
     */
    public static String path(String requestUri) {
        if (requestUri == null) {
            return "null";
        }
        String p = requestUri;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        for (String prefix : TOKEN_PATH_PREFIXES) {
            int at = p.indexOf(prefix);
            if (at < 0) {
                continue;
            }
            int start = at + prefix.length();
            int end = p.indexOf('/', start);
            String segment = end < 0 ? p.substring(start) : p.substring(start, end);
            if (segment.isEmpty() || "oidc".equals(segment)) {
                return p;
            }
            return p.substring(0, start) + lastFour(segment) + (end < 0 ? "" : p.substring(end));
        }
        return p;
    }
}
