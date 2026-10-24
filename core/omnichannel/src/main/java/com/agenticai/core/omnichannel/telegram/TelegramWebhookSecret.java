package com.agenticai.core.omnichannel.telegram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The {@code X-Telegram-Bot-Api-Secret-Token} secret is stored as its SHA-256 digest, never as the
 * token itself. The platform only ever has to recognise the value Telegram echoes back (it hands
 * the raw token to Telegram once, in {@code setWebhook}), so a database copy or backup cannot be
 * used to forge inbound messages as any paired chat. The token is 192 random bits, so an unsalted
 * digest is not guessable. The digest is 64 hex characters and fits the existing {@code
 * webhook_secret} column.
 */
public final class TelegramWebhookSecret {

    private TelegramWebhookSecret() {}

    /** The value to persist for {@code rawSecret}. */
    public static String hash(String rawSecret) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(rawSecret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Constant-time check of the header Telegram sent against the stored digest. A missing header
     * or a credential with no stored digest never matches.
     */
    public static boolean matches(String headerValue, String storedHash) {
        if (headerValue == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                hash(headerValue).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }
}
