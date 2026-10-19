package com.agenticai.core.omnichannel.imessage;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * iMessage handles are phone numbers or Apple-ID emails, and BlueBubbles reports the same person as
 * {@code +1 (555) 010-0100}, {@code +15550100100} or {@code Foo@Example.com} depending on the
 * route. Every comparison (the sender allow-list above all) goes through {@link #normalize} so the
 * spelling cannot be used to slip past, or fall out of, the list.
 */
public final class ImessageAddress {

    /** Upper bound on allow-list entries; a longer list is a mistake, not a use case. */
    public static final int MAX_ALLOWED_SENDERS = 200;

    private ImessageAddress() {}

    /**
     * Canonical form: lower-case email, or {@code +} followed by digits only. Returns null for
     * anything that is neither (blank, too short, letters mixed into a "phone").
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.indexOf('@') >= 0) {
            String email = s.toLowerCase(Locale.ROOT);
            int at = email.indexOf('@');
            boolean ok =
                    at > 0
                            && at == email.lastIndexOf('@')
                            && at < email.length() - 1
                            && email.indexOf(' ') < 0;
            return ok ? email : null;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c == '+' && i == 0) {
                continue;
            } else if (c == ' ' || c == '-' || c == '.' || c == '(' || c == ')') {
                continue;
            } else {
                return null;
            }
        }
        if (digits.length() < 7 || digits.length() > 15) {
            return null;
        }
        return "+" + digits;
    }

    /**
     * Parses a comma/semicolon/newline separated list into normalised handles; unusable entries
     * are dropped.
     */
    public static Set<String> parseList(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,;\\n\\r]+")) {
            String n = normalize(part);
            if (n != null && out.size() < MAX_ALLOWED_SENDERS) {
                out.add(n);
            }
        }
        return out;
    }

    /** Stored form of a list: one handle per line. */
    public static String serialize(Set<String> handles) {
        return String.join("\n", handles);
    }

    /** Last four characters for log lines; the full handle is personal data. */
    public static String mask(String handle) {
        if (handle == null || handle.length() < 4) {
            return "***";
        }
        return "***" + handle.substring(handle.length() - 4);
    }
}
