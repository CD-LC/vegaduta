package com.agenticai.core.omnichannel.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * An operator-written list of sender ids, parsed from newline, comma or whitespace separated text.
 * Matching is case-insensitive. An entry is one of:
 *
 * <ul>
 *   <li>an exact id ({@code @alice:example.org});
 *   <li>a domain pattern ({@code @*:example.org} or {@code *:example.org}) matching every id that
 *       ends with {@code :example.org};
 *   <li>{@code *}, matching everybody (an explicit opt-out of the list, never a default).
 * </ul>
 *
 * An empty list matches nobody. Entries are capped so a pasted file cannot grow a credential row
 * without bound; extra entries are ignored rather than failing the save.
 */
public final class SenderAllowList {

    public static final int MAX_ENTRIES = 200;
    public static final int MAX_ENTRY_LENGTH = 255;

    private final List<String> exact = new ArrayList<>();
    private final List<String> suffixes = new ArrayList<>();
    private boolean everyone;

    private SenderAllowList() {}

    public static SenderAllowList parse(String raw) {
        SenderAllowList list = new SenderAllowList();
        if (raw == null || raw.isBlank()) {
            return list;
        }
        int count = 0;
        for (String token : raw.split("[\\s,;]+")) {
            String entry = token.strip().toLowerCase(Locale.ROOT);
            if (entry.isEmpty() || entry.length() > MAX_ENTRY_LENGTH || count >= MAX_ENTRIES) {
                continue;
            }
            count++;
            if (entry.equals("*")) {
                list.everyone = true;
            } else if (entry.startsWith("@*:")) {
                list.suffixes.add(entry.substring(2));
            } else if (entry.startsWith("*:")) {
                list.suffixes.add(entry.substring(1));
            } else {
                list.exact.add(entry);
            }
        }
        return list;
    }

    public boolean isEmpty() {
        return !everyone && exact.isEmpty() && suffixes.isEmpty();
    }

    public boolean matches(String senderId) {
        if (senderId == null || senderId.isBlank()) {
            return false;
        }
        if (everyone) {
            return true;
        }
        String id = senderId.strip().toLowerCase(Locale.ROOT);
        if (exact.contains(id)) {
            return true;
        }
        for (String suffix : suffixes) {
            if (suffix.length() > 1 && id.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
