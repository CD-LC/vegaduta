package com.agenticai.core.tenancy.demo;

import java.security.SecureRandom;

/**
 * Generation for the short, human-typeable pairing code that ties a demo
 * sandbox to a messenger chat - {@code VD-7F3K}.
 *
 * <p><b>Why this is short when {@link DemoTokens} is deliberately 256 bits.</b>
 * They protect different things. The demo token is the only credential
 * standing between an anonymous caller and somebody else's sandbox, so it is
 * sized against offline guessing. A pairing code is read off a screen and
 * typed into a phone (or carried in a {@code t.me} deep link), it is
 * single-use, it is cleared the instant it is redeemed, and the worst
 * outcome of a successful guess is that a stranger's Telegram chat gets
 * attached to a message-capped, 5-day, FREE-plan sandbox containing nothing
 * but whatever that visitor typed at a demo agent. Twenty bits of
 * {@link SecureRandom} against a live-codes-only keyspace, behind the same
 * per-chat rate limiting every other inbound path has, is the proportionate
 * answer - and a 43-character Base64 string in a deep link would be
 * user-hostile for no real security gain.
 *
 * <p>The alphabet excludes {@code 0/O}, {@code 1/I/L} and vowels: no
 * transcription ambiguity when someone reads the code off a laptop, and no
 * accidental profanity in any of the ~1M possible codes.
 */
final class DemoPairingCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "BCDFGHJKMNPQRSTVWXYZ23456789".toCharArray();
    private static final String PREFIX = "VD-";
    private static final int BODY_LENGTH = 4;

    private DemoPairingCodes() {
    }

    static String newCode() {
        StringBuilder sb = new StringBuilder(PREFIX.length() + BODY_LENGTH);
        sb.append(PREFIX);
        for (int i = 0; i < BODY_LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * Accepts what a human actually types: lower case, missing prefix, a
     * space instead of the dash, a trailing full stop from autocorrect.
     * Returns null for anything that cannot be a code, so callers never run a
     * lookup on garbage.
     */
    private static final java.util.regex.Pattern EMBEDDED = java.util.regex.Pattern.compile(
        "(?<![A-Za-z0-9])[Vv][Dd][- ]?([A-Za-z0-9]{4})(?![A-Za-z0-9])"
    );

    /**
     * Finds a code INSIDE free text ("hi VD-7F3K please"), for channels like
     * WhatsApp where the visitor's client may wrap or edit the prefilled
     * message. Requires the {@code VD} prefix inside a sentence (a bare
     * 4-character word must never be mistaken for a code); a message that is
     * nothing but a code (with or without the prefix) also matches.
     */
    static String extract(String text) {
        if (text == null || text.length() > 400) {
            return null;
        }
        java.util.regex.Matcher m = EMBEDDED.matcher(text);
        while (m.find()) {
            String code = normalize("VD-" + m.group(1));
            if (code != null) {
                return code;
            }
        }
        return normalize(text);
    }

    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder body = new StringBuilder(BODY_LENGTH);
        for (char c : raw.trim().toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                body.append(c);
            }
        }
        String compact = body.toString();
        if (compact.startsWith("VD")) {
            compact = compact.substring(2);
        }
        if (compact.length() != BODY_LENGTH) {
            return null;
        }
        for (char c : compact.toCharArray()) {
            if (new String(ALPHABET).indexOf(c) < 0) {
                return null;
            }
        }
        return PREFIX + compact;
    }
}
