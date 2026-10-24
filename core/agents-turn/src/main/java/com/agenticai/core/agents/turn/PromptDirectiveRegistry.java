package com.agenticai.core.agents.turn;

import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptDefinition;
import com.agenticai.core.prompts.PromptPlaceholder;
import com.agenticai.core.prompts.PromptResolver;
import java.util.List;
import java.util.Optional;

/**
 * The platform's standing prompt directives under their original short keys ({@code tool_trust},
 * {@code current_date}, ...), the vocabulary of the owner console's {@code /api/prompt-directives}
 * routes and of the tenant settings that predate the prompt registry. Each entry is a view of
 * registry keys: its title, description, risk flag, placeholders and default text come from the
 * default resource files under {@code prompts/en/directive.*.md} (core/agents-turn), so the text a
 * turn sends, the text the console shows as the default and the text a reset restores are one
 * thing. Everything that can actually change a prompt (scopes, versions, per-agent overrides)
 * lives in {@code core/prompts}; see {@link PromptDirectiveSettings}.
 *
 * <p>A directive with two wordings ({@code tool_trust} full and compact, {@code context_compaction}
 * with and without the restore tool) is two registry keys; the legacy API reads the first and
 * writes both, which is what the single legacy override always did.
 */
public final class PromptDirectiveRegistry {

    private PromptDirectiveRegistry() {}

    /** Reserved legacy key of the free-text block appended after the built-ins for every agent. */
    public static final String CUSTOM_INSTRUCTIONS_KEY = "custom_instructions";

    public static final String CURRENT_DATE = "current_date";
    public static final String MEMORY_USAGE = "memory_usage";
    public static final String MEDIA_HONESTY = "media_honesty";
    public static final String TOOL_TRUST = "tool_trust";
    public static final String AGENTIC_COMPLETION = "agentic_completion";
    public static final String MCP_UNTRUSTED = "mcp_untrusted";
    public static final String TOOL_SEARCH = "tool_search";
    public static final String CONTEXT_COMPACTION = "context_compaction";
    public static final String BROWSER_V2 = "browser_v2";
    public static final String ARTIFACTS = "artifacts";

    /** Registry keys ({@code prompts/en/<key>.md}) of the standing directives. */
    public static final class Keys {
        private Keys() {}

        public static final String CURRENT_DATE = "directive.current_date";
        public static final String MEMORY_USAGE = "directive.memory_usage";
        public static final String MEDIA_HONESTY = "directive.media_honesty";
        public static final String TOOL_TRUST = "directive.tool_trust";
        public static final String TOOL_TRUST_COMPACT = "directive.tool_trust.compact";
        public static final String AGENTIC_COMPLETION = "directive.agentic_completion";
        public static final String MCP_UNTRUSTED = "directive.mcp_untrusted";
        public static final String TOOL_SEARCH = "directive.tool_search";
        public static final String CONTEXT_COMPACTION = "directive.context_compaction";
        public static final String CONTEXT_COMPACTION_NO_RESTORE = "directive.context_compaction.no_restore";
        public static final String BROWSER_V2 = "directive.browser_v2";
        public static final String ARTIFACTS = "directive.artifacts";
        public static final String CUSTOM_INSTRUCTIONS = "directive.custom_instructions";
    }

    /**
     * One built-in directive. {@code defaultText} is empty for a retired directive that currently
     * sends nothing (its rule moved into tool_trust); such an entry takes no text override.
     * {@code promptKeys} are the registry keys it covers, the first being the one shown.
     */
    public record Entry(
            String key,
            String title,
            String description,
            boolean securityCritical,
            String defaultText,
            List<String> placeholders,
            List<String> promptKeys) {

        /** True when the platform currently has text for this directive to replace. */
        public boolean overridable() {
            return !defaultText.isEmpty();
        }

        public String primaryPromptKey() {
            return promptKeys.get(0);
        }
    }

    private static final List<String[]> LEGACY =
            List.of(
                    new String[] {CURRENT_DATE, Keys.CURRENT_DATE},
                    new String[] {MEMORY_USAGE, Keys.MEMORY_USAGE},
                    new String[] {MEDIA_HONESTY, Keys.MEDIA_HONESTY},
                    new String[] {TOOL_TRUST, Keys.TOOL_TRUST, Keys.TOOL_TRUST_COMPACT},
                    new String[] {AGENTIC_COMPLETION, Keys.AGENTIC_COMPLETION},
                    new String[] {MCP_UNTRUSTED, Keys.MCP_UNTRUSTED},
                    new String[] {TOOL_SEARCH, Keys.TOOL_SEARCH},
                    new String[] {CONTEXT_COMPACTION, Keys.CONTEXT_COMPACTION, Keys.CONTEXT_COMPACTION_NO_RESTORE},
                    new String[] {BROWSER_V2, Keys.BROWSER_V2},
                    new String[] {ARTIFACTS, Keys.ARTIFACTS});

    private static volatile List<Entry> entries;

    /** All built-ins, in the order they appear in the prompt. */
    public static List<Entry> all() {
        List<Entry> e = entries;
        if (e == null) {
            e = build(PromptResolver.defaultsOnly().catalog());
            entries = e;
        }
        return e;
    }

    public static Optional<Entry> find(String key) {
        return all().stream().filter(e -> e.key().equals(key)).findFirst();
    }

    /** The registry keys behind a legacy key (empty for an unknown one, including custom_instructions). */
    public static List<String> promptKeys(String legacyKey) {
        if (CUSTOM_INSTRUCTIONS_KEY.equals(legacyKey)) {
            return List.of(Keys.CUSTOM_INSTRUCTIONS);
        }
        return find(legacyKey).map(Entry::promptKeys).orElse(List.of());
    }

    static List<Entry> build(PromptCatalog catalog) {
        List<Entry> out = new java.util.ArrayList<>();
        for (String[] row : LEGACY) {
            String legacy = row[0];
            List<String> keys = List.of(row).subList(1, row.length);
            PromptDefinition def = catalog.find(keys.get(0)).orElseThrow(() -> new IllegalStateException("Missing prompt resource for " + keys.get(0)));
            out.add(
                    new Entry(
                            legacy,
                            def.title(),
                            def.description(),
                            def.securityCritical(),
                            catalog.defaultText(def.key()),
                            def.placeholders().stream().map(PromptPlaceholder::name).toList(),
                            keys));
        }
        return List.copyOf(out);
    }
}
