package com.agenticai.core.common.privacy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text handling for the consent step: reading a contact's answer to the notice and composing
 * the notice. No I/O so it is unit-testable on its own.
 */
final class ConsentText {

    enum Intent {
        ACCEPT,
        DECLINE,
        WITHDRAW,
        LANGUAGE,
        OTHER
    }

    record Parsed(Intent intent, String language) {}

    /** Per-policy keyword overrides; an empty list for a key means "use the built-in words". */
    record Keywords(List<String> accept, List<String> decline, List<String> withdraw) {
        static final Keywords DEFAULTS = new Keywords(List.of(), List.of(), List.of());
    }

    private static final Set<String> DEFAULT_ACCEPT =
            Set.of("yes", "i agree", "agree", "i consent", "consent", "haan", "हाँ", "हां", "सहमत");
    private static final Set<String> DEFAULT_DECLINE =
            Set.of("no", "i do not agree", "i disagree", "disagree", "decline", "nahi", "नहीं");
    private static final Set<String> DEFAULT_WITHDRAW =
            Set.of(
                    "stop",
                    "withdraw",
                    "withdraw consent",
                    "revoke",
                    "revoke consent",
                    "opt out",
                    "optout",
                    "unsubscribe");

    private static final Pattern LANGUAGE =
            Pattern.compile("^lang(?:uage)?\\s+([a-z]{2,3}(?:-[a-z0-9]{2,8})?)$");

    static final String DEFAULT_INSTRUCTIONS =
            "Reply YES to agree or NO to decline. Reply STOP at any time to withdraw your consent."
                    + " Reply LANG <code> (for example LANG hi) to read this notice in another"
                    + " language.";

    private ConsentText() {}

    static String normalize(String message) {
        if (message == null) {
            return "";
        }
        String s = message.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[\\p{Punct}।!]+$", "").trim();
        return s.replaceAll("\\s+", " ");
    }

    static Parsed parse(String message, Keywords keywords) {
        String s = normalize(message);
        Matcher m = LANGUAGE.matcher(s);
        if (m.matches()) {
            return new Parsed(Intent.LANGUAGE, m.group(1));
        }
        if (matches(s, keywords.withdraw(), DEFAULT_WITHDRAW)) {
            return new Parsed(Intent.WITHDRAW, null);
        }
        if (matches(s, keywords.decline(), DEFAULT_DECLINE)) {
            return new Parsed(Intent.DECLINE, null);
        }
        if (matches(s, keywords.accept(), DEFAULT_ACCEPT)) {
            return new Parsed(Intent.ACCEPT, null);
        }
        return new Parsed(Intent.OTHER, null);
    }

    private static boolean matches(String s, List<String> custom, Set<String> defaults) {
        if (custom != null && !custom.isEmpty()) {
            return custom.stream().anyMatch(k -> normalize(k).equals(s));
        }
        return defaults.contains(s);
    }

    /** The text actually sent: the tenant's notice plus the "how to answer" line. */
    static String compose(String notice, String instructions) {
        String how = instructions == null || instructions.isBlank() ? DEFAULT_INSTRUCTIONS : instructions;
        return notice.strip() + "\n\n" + how.strip();
    }

    /** The notice text for {@code language}, falling back to the default language's. */
    static String pick(Map<String, String> notices, String language, String defaultLanguage) {
        if (language != null && notices.containsKey(language) && !notices.get(language).isBlank()) {
            return notices.get(language);
        }
        return notices.get(defaultLanguage);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
