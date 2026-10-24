package com.agenticai.core.agents.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import com.agenticai.core.prompts.PromptResolver;

/**
 * Pure helpers for the user-visible, always-in-context memory blocks (gap-analysis item 11).
 *
 * <p><b>Storage (no migration):</b> a block is one {@code tenant_memory} row whose {@code subject_key}
 * is {@code <subject>#mem:<label>}. {@code tenant_memory.recall} filters on an exact {@code
 * subject_key}, so these rows never surface as ordinary recalled facts, and {@link
 * TenantMemoryService} excludes them from its oldest-first prune. The {@code agent_memory} /
 * {@code tenant_memory} tables have no label or type column; adding one would need a Flyway
 * migration, which this item deliberately does not do.
 *
 * <p>Labels: the three fixed blocks {@value #PERSONA}, {@value #USER_PROFILE}, {@value #PROJECT_NOTES}
 * (always rendered when non-empty), plus any number of named project notes {@code project:<slug>}
 * (freelancers keep one per client) which are rendered only when the user's message mentions the slug,
 * so they cost nothing on unrelated turns.
 *
 * <p>{@value #ACTIVE_PROJECT} is a reserved pointer, not a note: its content is the slug of the project
 * the user is working on right now. It is never rendered as a block of its own; it makes that one
 * project's note render on every turn (as if each message named it) until the user switches.
 */
public final class MemoryBlocks {

    public static final String PERSONA = "persona";
    public static final String USER_PROFILE = "user_profile";
    public static final String PROJECT_NOTES = "project_notes";
    public static final String PROJECT_PREFIX = "project:";
    /** Reserved label whose content is the slug of the user's active project (see class javadoc). */
    public static final String ACTIVE_PROJECT = "active_project";
    /** Separator between the real subject key and the block label inside {@code subject_key}. */
    public static final String KEY_MARKER = "#mem:";

    public static final List<String> FIXED_LABELS = List.of(PERSONA, USER_PROFILE, PROJECT_NOTES);

    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{2,39}");
    private static final int MAX_SUBJECT_KEY = 255;
    /** Default total character budget for all rendered blocks together (labels and headers excluded). */
    public static final int DEFAULT_MAX_TOTAL_CHARS = 4000;

    /** Registry key of the line that frames the rendered blocks (see {@code prompts/en/memory.notes.md}). */
    public static final String NOTES_KEY = "memory.notes";

    private MemoryBlocks() {
    }

    /** The shipped wording of the line that opens the rendered blocks. */
    public static String defaultNotesHeader() {
        return PromptResolver.defaultsOnly().catalog().defaultText(NOTES_KEY);
    }

    /**
     * {@code rendered} (the output of {@link #render}) with its opening line replaced by {@code header}:
     * how an owner's reworded {@value #NOTES_KEY} reaches the text without the render path knowing
     * about overrides. {@code rendered} is returned unchanged when {@code header} is null, equals the
     * shipped line, or {@code rendered} does not start with the shipped line.
     */
    public static String withNotesHeader(String rendered, String header) {
        String shipped = defaultNotesHeader();
        if (rendered == null || header == null || header.equals(shipped) || !rendered.startsWith(shipped)) {
            return rendered;
        }
        return header + rendered.substring(shipped.length());
    }

    /** Lower-cased, trimmed label; {@code null} when it is not a legal block label. */
    public static String normalizeLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String label = raw.trim().toLowerCase(Locale.ROOT);
        if (FIXED_LABELS.contains(label) || ACTIVE_PROJECT.equals(label)) {
            return label;
        }
        if (label.startsWith(PROJECT_PREFIX) && SLUG.matcher(label.substring(PROJECT_PREFIX.length())).matches()) {
            return label;
        }
        return null;
    }

    public static boolean isProjectLabel(String label) {
        return label != null && label.startsWith(PROJECT_PREFIX);
    }

    /**
     * The project slug an {@value #ACTIVE_PROJECT} block points at, or {@code null} when {@code content}
     * is blank or not a legal slug. Accepts the bare slug or the full {@code project:<slug>} label.
     */
    public static String activeProjectSlug(String content) {
        if (content == null) {
            return null;
        }
        String slug = content.trim().toLowerCase(Locale.ROOT);
        if (slug.startsWith(PROJECT_PREFIX)) {
            slug = slug.substring(PROJECT_PREFIX.length());
        }
        return SLUG.matcher(slug).matches() ? slug : null;
    }

    /**
     * True when {@code subject} is usable as a block owner: non-blank and free of {@link #KEY_MARKER}. A
     * subject containing the marker could otherwise collide with another subject's block rows.
     */
    public static boolean isSafeSubject(String subject) {
        return subject != null && !subject.isBlank() && !subject.contains(KEY_MARKER);
    }

    /** {@code subject_key} value for one block, or {@code null} when unsafe or it would not fit the 255-char column. */
    public static String storageKey(String subject, String label) {
        if (!isSafeSubject(subject)) {
            return null;
        }
        String key = subject + KEY_MARKER + label;
        return key.length() > MAX_SUBJECT_KEY ? null : key;
    }

    /** Prefix shared by every block row of one subject. */
    public static String storagePrefix(String subject) {
        return subject + KEY_MARKER;
    }

    /**
     * Label of a stored block row, given the subject it was listed for; {@code null} when the suffix is not
     * a canonical legal label (so crafted rows from other subjects are never treated as blocks).
     */
    public static String labelOf(String subject, String storedKey) {
        String prefix = storagePrefix(subject);
        if (storedKey == null || !storedKey.startsWith(prefix)) {
            return null;
        }
        String suffix = storedKey.substring(prefix.length());
        String normalized = normalizeLabel(suffix);
        return suffix.equals(normalized) ? normalized : null;
    }

    /**
     * True when {@code message} mentions the project slug as a whole word or phrase
     * ({@code acme-redesign} also matches "acme redesign"); "ai" never matches inside "maintain".
     */
    static boolean projectMentioned(String slug, String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return wholeWord(lower, slug) || (slug.indexOf('-') >= 0 && wholeWord(lower, slug.replace('-', ' ')));
    }

    private static boolean wholeWord(String haystack, String needle) {
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            int end = at + needle.length();
            boolean startOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            boolean endOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    /**
     * Renders the always-in-context block text, or {@code null} when nothing applies. Only non-empty
     * blocks are included; each is hard-capped at {@code maxCharsPerBlock} characters. Fixed blocks come
     * first in a stable order (stable text keeps the prompt cache prefix steady), then mentioned projects.
     * The project named by an {@value #ACTIVE_PROJECT} entry counts as mentioned on every turn; that
     * entry itself is never rendered.
     *
     * @param blocks label to content, any order
     */
    public static String render(Map<String, String> blocks, String userMessage, int maxCharsPerBlock) {
        return render(blocks, userMessage, maxCharsPerBlock, DEFAULT_MAX_TOTAL_CHARS);
    }

    /** As {@link #render(Map, String, int)} with a total budget; blocks past the budget are dropped or truncated. */
    public static String render(Map<String, String> blocks, String userMessage, int maxCharsPerBlock, int maxTotalChars) {
        Rendered rendered = renderWithLabels(blocks, userMessage, maxCharsPerBlock, maxTotalChars);
        return rendered == null ? null : rendered.text();
    }

    /** Rendered block text plus the labels that actually made it in, in render order. */
    public record Rendered(String text, List<String> labels) {
    }

    /**
     * Same text as {@link #render(Map, String, int, int)}, together with the labels of the blocks that
     * were rendered (empty and over-budget blocks are not listed). {@code null} when nothing applies.
     */
    public static Rendered renderWithLabels(Map<String, String> blocks, String userMessage, int maxCharsPerBlock, int maxTotalChars) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        int cap = Math.max(1, maxCharsPerBlock);
        List<String> labels = new ArrayList<>();
        for (String fixed : FIXED_LABELS) {
            if (blocks.containsKey(fixed)) {
                labels.add(fixed);
            }
        }
        String activeSlug = activeProjectSlug(blocks.get(ACTIVE_PROJECT));
        String activeLabel = activeSlug == null ? null : PROJECT_PREFIX + activeSlug;
        blocks.keySet().stream()
            .filter(MemoryBlocks::isProjectLabel)
            .sorted()
            .filter(l -> l.equals(activeLabel) || projectMentioned(l.substring(PROJECT_PREFIX.length()), userMessage))
            .forEach(labels::add);

        StringBuilder out = new StringBuilder();
        List<String> renderedLabels = new ArrayList<>();
        int budget = Math.max(1, maxTotalChars);
        for (String label : labels) {
            if (budget <= 0) {
                break;
            }
            String content = blocks.get(label);
            if (content == null || content.isBlank()) {
                continue;
            }
            String text = content.strip();
            if (text.length() > cap) {
                text = text.substring(0, cap) + "...";
            }
            if (text.length() > budget) {
                text = text.substring(0, budget) + "...";
            }
            budget -= text.length();
            if (out.isEmpty()) {
                out.append(defaultNotesHeader());
            }
            out.append("\n[").append(label).append("]\n").append(text);
            renderedLabels.add(label);
        }
        return out.isEmpty() ? null : new Rendered(out.toString(), List.copyOf(renderedLabels));
    }
}
