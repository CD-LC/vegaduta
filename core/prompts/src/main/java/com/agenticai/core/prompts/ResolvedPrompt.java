package com.agenticai.core.prompts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The answer of {@link PromptResolver#resolve}: the text to send and where it came from.
 *
 * @param key the key asked for
 * @param text the text with placeholders substituted; {@code null} when the winning override
 *     switched the prompt off ({@link #enabled()} is false) or the key is unknown ({@link
 *     #known()} is false)
 * @param source see {@link PromptSource}
 * @param scope the scope of the winning override; null for a default or fallback
 * @param version the version of the winning override; 0 for the classpath default
 * @param locale the locale of the text
 * @param enabled false when the prompt was switched off at the winning scope
 * @param known false when no such key is registered (a call-site defect; logged)
 * @param hash first 12 hex characters of the SHA-256 of the unrendered text, so two blocks can be
 *     told apart without printing them
 * @param estimatedTokens rough size of {@link #text()}
 */
public record ResolvedPrompt(
        String key,
        String text,
        PromptSource source,
        PromptScope scope,
        int version,
        String locale,
        boolean enabled,
        boolean known,
        String hash,
        int estimatedTokens) {

    /** True when there is text to send. */
    public boolean active() {
        return enabled && known && text != null && !text.isBlank();
    }

    /** The text to send, or null (switched off, unknown, or blank). */
    public String textOrNull() {
        return active() ? text : null;
    }

    public static ResolvedPrompt unknown(String key) {
        return new ResolvedPrompt(key, null, PromptSource.FALLBACK, null, 0, null, true, false, "", 0);
    }

    static String hashOf(String text) {
        if (text == null) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
