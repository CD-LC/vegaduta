package com.agenticai.core.secrets;

import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * Scrubs resolved secret values out of anything travelling back toward the
 * model, the transcript, or a log line.
 *
 * <p>This is the second half of the contract described on
 * {@link SecretReferenceResolver}. Resolution keeps a secret out of the
 * model's context on the way <i>in</i>; redaction keeps it out on the way
 * <i>out</i>. Both are needed, because the common leak is not the config - it
 * is a tool that helpfully echoes the request it just made, or a connection
 * error that quotes the Authorization header it failed with. The platform
 * already learned this once: {@code DynamicMcpConnectionManager#redactSecrets}
 * exists precisely because MCP connection errors were persisting env values
 * into a database column. This generalises that fix.
 *
 * <p>Value-based rather than pattern-based on purpose. A regex for "things
 * that look like API keys" both misses real secrets (a password is just a
 * word) and mangles innocent text. Here the exact strings that were
 * substituted moments ago are known, so matching is exact and complete.
 */
@Component
public class SecretRedactor {

    static final String MASK = "[redacted:secret]";

    /**
     * Below this length, replacing every occurrence does more harm than good:
     * a 4-character secret is likely to appear inside ordinary words and
     * numbers in the tool output, and blanking those would corrupt the result
     * the agent is trying to reason about while barely protecting anything.
     * Such a secret should not have been stored in the first place.
     */
    private static final int MIN_REDACTABLE_LENGTH = 6;

    /**
     * Replaces every occurrence of every supplied value.
     *
     * @param text   tool output, error message, or log line. Null-safe.
     * @param values the plaintext values from
     *               {@link SecretReferenceResolver.Resolution#resolvedValues()}.
     */
    public String redact(String text, Collection<String> values) {
        if (text == null || text.isEmpty() || values == null || values.isEmpty()) {
            return text;
        }
        String result = text;
        for (String value : values) {
            for (String variant : variantsOf(value)) {
                // Plain replace, not regex: a secret can contain any character,
                // and compiling it as a pattern would either throw on an
                // unbalanced bracket or match the wrong thing.
                result = result.replace(variant, MASK);
            }
        }
        return result;
    }

    /**
     * True if any supplied value still appears in the text, in any of the forms
     * {@link #redact} knows how to scrub - for assertions and tests.
     *
     * <p>Checks the same variant set rather than just the raw value on purpose.
     * A leak test that only looked for the raw form would pass while a
     * JSON-escaped or base64'd copy of the secret sat in the very string being
     * asserted on, which is the more likely shape of a real leak.
     */
    public boolean containsAny(String text, Collection<String> values) {
        if (text == null || values == null) {
            return false;
        }
        for (String value : values) {
            for (String variant : variantsOf(value)) {
                if (text.contains(variant)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Every form one secret can take in text that is heading back toward the
     * model: as written, URL-encoded into a query string, base64'd into a Basic
     * header, and JSON-escaped by serialization.
     *
     * <p>Shared by {@link #redact} and {@link #containsAny} so the two can
     * never disagree about what counts as present - a redactor that scrubs a
     * form its own leak check does not look for is worse than neither.
     */
    private java.util.Set<String> variantsOf(String value) {
        if (value == null || value.length() < MIN_REDACTABLE_LENGTH) {
            return java.util.Set.of();
        }
        java.util.Set<String> variants = new java.util.LinkedHashSet<>();
        variants.add(value);
        variants.add(urlEncode(value));
        variants.add(java.util.Base64.getEncoder()
            .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        variants.add(jsonEscape(value));
        return variants;
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The body of the JSON string literal this value would serialize to -
     * i.e. what a secret looks like once it is inside a JSON document, without
     * the surrounding quotes.
     *
     * <p>Hand-rolled rather than delegating to Jackson so this module stays a
     * near-leaf (it needs no ObjectMapper for anything else) and so the escape
     * set is visible at the point where the security argument is made. It
     * covers RFC 8259's two mandatory escapes plus the control characters,
     * which is every rewrite a conforming serializer can apply to the
     * characters a secret realistically contains. A serializer that also
     * escapes non-ASCII to \\uXXXX would defeat this, but neither Jackson's
     * default nor the sandbox's output does that.
     */
    private String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
