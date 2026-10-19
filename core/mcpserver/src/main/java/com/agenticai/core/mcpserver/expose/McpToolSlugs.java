package com.agenticai.core.mcpserver.expose;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Pure helpers that turn a resource name into a legal, collision-safe MCP tool name.
 *
 * <p>MCP clients are strict about tool names (Claude Desktop and most hosted clients accept
 * {@code [a-zA-Z0-9_-]{1,64}}), so every name is lower-case ASCII letters, digits and underscores,
 * at most {@value #MAX_TOOL_NAME_LENGTH} characters, and starts with the kind prefix
 * ({@code agent_} / {@code workflow_}). Allocation is deterministic given the set of names
 * already taken, and the caller persists the result: the name is chosen once, when a resource is
 * first exposed, and then never recomputed - so renaming an agent cannot silently change the
 * tool name an external client has already wired up.
 */
public final class McpToolSlugs {

    static final int MAX_TOOL_NAME_LENGTH = 64;
    /** Slug budget: 64 - the longest prefix ("workflow_", 9) - room for a "_NNN" collision suffix. */
    static final int MAX_SLUG_LENGTH = 40;
    private static final int MAX_NUMERIC_SUFFIX = 999;

    private McpToolSlugs() {
        // utility class
    }

    /**
     * Lower-case ASCII slug of {@code name}: accents folded, every run of other characters becomes
     * one underscore, leading/trailing underscores trimmed, capped at {@link #MAX_SLUG_LENGTH}.
     * A name with nothing usable in it (all symbols, or non-Latin script) yields {@code "unnamed"}.
     */
    public static String slugify(String name) {
        if (name == null) {
            return "unnamed";
        }
        String folded = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT);
        String slug = folded.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH).replaceAll("_+$", "");
        }
        return slug.isEmpty() ? "unnamed" : slug;
    }

    /**
     * The first free tool name for {@code name} under {@code kind}: {@code agent_support_bot}, then
     * {@code agent_support_bot_2}, {@code agent_support_bot_3}, ... Falls back to a name carrying
     * the resource id's first eight hex characters if a thousand siblings already share the slug,
     * so allocation always terminates with a unique, legal name.
     *
     * @param taken every tool name already allocated in this tenant (any kind)
     */
    public static String allocate(McpExposureKind kind, String name, UUID resourceId, Set<String> taken) {
        String base = kind.toolPrefix() + slugify(name);
        if (!taken.contains(base)) {
            return base;
        }
        for (int suffix = 2; suffix <= MAX_NUMERIC_SUFFIX; suffix++) {
            String candidate = base + "_" + suffix;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        String idPart = resourceId == null
            ? Long.toHexString(System.nanoTime())
            : resourceId.toString().replace("-", "").substring(0, 8);
        return base + "_" + idPart;
    }

    /** True for a name this module could have allocated - the cheap pre-check the RPC handler uses to decide whether a tools/call is its business. */
    public static boolean isDynamicToolName(String name) {
        return name != null
            && (name.startsWith(McpExposureKind.AGENT.toolPrefix()) || name.startsWith(McpExposureKind.WORKFLOW.toolPrefix()));
    }
}
