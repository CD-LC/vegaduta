package com.agenticai.core.agents.skills;

import java.util.List;

/**
 * Level 1 of progressive disclosure: the block appended to an agent's system prompt listing every
 * skill it may trigger as {@code name: short description} (first 30 words), and nothing else. The
 * block is only requested on turns where {@code load_skill} is offered (see {@link #anyMatch}). A
 * skill's instructions and files are absent until the model calls {@code load_skill}, which is what
 * lets a workspace install dozens of skills for roughly 100 tokens each.
 *
 * <p>Pure function of its input and order-stable, so the block is byte-identical turn to turn and
 * stays inside the provider's cacheable prompt prefix.
 */
public final class SkillPromptBlock {

    /**
     * Hard ceiling on Level 1 entries - a backstop so a huge catalog can never crowd out the real
     * prompt.
     */
    public static final int MAX_SKILLS_IN_PROMPT = 60;

    /**
     * Character budget of the whole Level 1 block (about 2,300 tokens). Descriptions are kept
     * while they fit; see {@link #render}.
     */
    public static final int MAX_BLOCK_CHARS = 9_000;

    /** Extra room, past the budget, that bare names may still use before the list is cut. */
    private static final int NAMES_ONLY_HEADROOM = 2_000;

    private SkillPromptBlock() {}

    /** Empty string when there is nothing to offer - callers can append unconditionally. */
    public static String render(List<SkillService.SkillMetadata> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Skills\n")
                .append(
                        "Before you answer, check whether the request matches one of these skills. If it does, your FIRST ")
                .append(
                        "action must be to call load_skill(name), then follow its instructions - do not answer from memory first. ")
                .append(
                        "Read bundled files with read_skill_file only if needed. Skip skills that do not match and ")
                .append("do not mention skills to the user unless asked.\n");
        // Level 1 has a size budget, not just an entry ceiling: entries keep their description
        // while the block is under MAX_BLOCK_CHARS, then shrink to bare names (still loadable),
        // and anything that does not fit is counted instead of silently vanishing.
        int listed = 0;
        boolean namesOnly = false;
        for (SkillService.SkillMetadata skill : skills) {
            if (listed >= MAX_SKILLS_IN_PROMPT) {
                break;
            }
            String detailed = "- " + skill.name() + ": " + brief(skill.description()) + "\n";
            String bare = "- " + skill.name() + "\n";
            if (!namesOnly && sb.length() + detailed.length() <= MAX_BLOCK_CHARS) {
                sb.append(detailed);
            } else if (sb.length() + bare.length() <= MAX_BLOCK_CHARS + NAMES_ONLY_HEADROOM) {
                namesOnly = true;
                sb.append(bare);
            } else {
                break;
            }
            listed++;
        }
        if (listed < skills.size()) {
            sb.append("- (")
                    .append(skills.size() - listed)
                    .append(" more skills are installed but not listed here)\n");
        }
        return sb.toString();
    }

    /**
     * Small-model mode (V501): names only. The descriptions of 20+ skills are the single largest
     * block of standing text a small model sees; it reads a short list of names and calls {@code
     * load_skill} for the one that matches, which brings the full instructions for that skill only.
     */
    public static String renderCompact(List<SkillService.SkillMetadata> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## Skills\n")
                .append("If the request matches one of these skills, first call load_skill(name) and follow it: ");
        sb.append(
                skills.stream()
                        .limit(MAX_SKILLS_IN_PROMPT)
                        .map(SkillService.SkillMetadata::name)
                        .collect(java.util.stream.Collectors.joining(", ")));
        return sb.append(".\n").toString();
    }

    /**
     * Long enough to keep a description's "Use when..." trigger: at 12 words it was cut before the
     * condition ("...structured brainstorming techniques. Use when the..."), so the model could not
     * tell when a skill applied and answered from its persona instead of calling load_skill.
     */
    private static final int MAX_DESCRIPTION_WORDS = 30;

    private static final java.util.Set<String> STOP =
            java.util.Set.of(
                    "which", "would", "their", "using", "about", "please", "these", "those",
                    "there", "where", "should", "could", "other", "after", "before", "while",
                    "every", "users", "being", "needs");

    /** At most {@link #MAX_DESCRIPTION_WORDS} words, single line. */
    static String brief(String text) {
        String[] words = singleLine(text == null ? "" : text).split(" ");
        if (words.length <= MAX_DESCRIPTION_WORDS) {
            return String.join(" ", words);
        }
        return String.join(" ", java.util.Arrays.copyOf(words, MAX_DESCRIPTION_WORDS)) + "...";
    }

    private static java.util.Set<String> tokens(String text, int minLen) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (text == null) {
            return out;
        }
        for (String t : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= minLen) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Common non-skill-name words mapped to the built-in skill name they imply, so "make me a
     * spreadsheet" reaches the xlsx skill. Keys are lower-case message tokens.
     */
    private static final java.util.Map<String, String> SYNONYMS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("spreadsheet", "xlsx"),
                    java.util.Map.entry("spreadsheets", "xlsx"),
                    java.util.Map.entry("excel", "xlsx"),
                    java.util.Map.entry("sheet", "xlsx"),
                    java.util.Map.entry("sheets", "xlsx"),
                    java.util.Map.entry("word", "docx"),
                    java.util.Map.entry("document", "docx"),
                    java.util.Map.entry("documents", "docx"),
                    java.util.Map.entry("doc", "docx"),
                    java.util.Map.entry("docs", "docx"),
                    java.util.Map.entry("slides", "pptx"),
                    java.util.Map.entry("slide", "pptx"),
                    java.util.Map.entry("deck", "pptx"),
                    java.util.Map.entry("powerpoint", "pptx"),
                    java.util.Map.entry("presentation", "pptx"),
                    java.util.Map.entry("presentations", "pptx"),
                    java.util.Map.entry("ppt", "pptx"),
                    java.util.Map.entry("sql", "sql-query"),
                    java.util.Map.entry("query", "sql-query"),
                    java.util.Map.entry("queries", "sql-query"),
                    java.util.Map.entry("database", "sql-query"),
                    java.util.Map.entry("databases", "sql-query"),
                    java.util.Map.entry("seo", "seo-content"));

    /**
     * True when the message names a skill (or a common synonym of it, see {@link #SYNONYMS}), or
     * shares two distinctive words with its description. Tokenising is Unicode-aware, so a
     * non-English message that embeds an English term such as "excel" still matches; a message with
     * no such term is covered by the sticky history check in the caller.
     */
    public static boolean anyMatch(List<SkillService.SkillMetadata> skills, String userMessage) {
        if (skills == null || skills.isEmpty() || userMessage == null || userMessage.isBlank()) {
            return false;
        }
        java.util.Set<String> msg = tokens(userMessage, 3);
        java.util.Set<String> implied = new java.util.HashSet<>();
        for (String t : tokens(userMessage, 2)) {
            String target = SYNONYMS.get(t);
            if (target != null) {
                implied.add(target);
            }
        }
        for (SkillService.SkillMetadata s : skills) {
            if (s.name() != null && implied.contains(s.name().toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
            for (String n : tokens(s.name(), 3)) {
                if (msg.contains(n)) {
                    return true;
                }
            }
            int overlap = 0;
            for (String d : tokens(s.description(), 5)) {
                if (!STOP.contains(d) && msg.contains(d) && ++overlap >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Past user turns remembered per session for the sticky gate. */
    static final int HISTORY_TURNS = 6;

    private static final int MAX_TRACKED_SESSIONS = 2000;

    /**
     * Per-session sticky gate: skills stay on for a conversation once they were relevant, so a
     * follow-up like "yes, go ahead" does not drop {@code load_skill} (and churn the cached prompt
     * prefix) mid-task. Offered when the current message matches, OR any of the last {@link
     * #HISTORY_TURNS} messages in this session matched, OR a skill was already loaded in it.
     * In-memory and bounded (LRU); losing it on restart only costs one un-sticky turn.
     */
    public static final class SessionGate {
        private static final class State {
            final java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
            boolean loaded;
        }

        private final java.util.Map<String, State> sessions =
                new java.util.LinkedHashMap<>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(java.util.Map.Entry<String, State> eldest) {
                        return size() > MAX_TRACKED_SESSIONS;
                    }
                };

        /**
         * True when skills should be offered this turn. A null key (no session) falls back to the
         * current message only.
         */
        public boolean shouldOffer(
                String key, List<SkillService.SkillMetadata> skills, String userMessage) {
            if (key == null) {
                return anyMatch(skills, userMessage);
            }
            java.util.List<String> past;
            boolean loaded;
            synchronized (sessions) {
                State st = sessions.computeIfAbsent(key, k -> new State());
                past = new java.util.ArrayList<>(st.recent);
                loaded = st.loaded;
                if (userMessage != null
                        && !userMessage.isBlank()
                        && !userMessage.equals(st.recent.peekLast())) {
                    st.recent.addLast(userMessage);
                    while (st.recent.size() > HISTORY_TURNS) {
                        st.recent.removeFirst();
                    }
                }
            }
            if (loaded || anyMatch(skills, userMessage)) {
                return true;
            }
            for (String m : past) {
                if (anyMatch(skills, m)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Called when load_skill actually ran in this session; keeps the skill tools on for good.
         */
        public void markLoaded(String key) {
            if (key == null) {
                return;
            }
            synchronized (sessions) {
                sessions.computeIfAbsent(key, k -> new State()).loaded = true;
            }
        }
    }

    /**
     * A description is one list item; a newline inside it must not be able to start a fake new
     * section.
     */
    private static String singleLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
