package com.agenticai.core.prompts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The holder a call site keeps for the registry, so a hand-built object (a test, a static helper)
 * works with the shipped defaults and a Spring bean gets the real {@link PromptResolver} through one
 * optional setter. It exists so a class does not need a new constructor argument to read its prompt
 * from the registry.
 *
 * <pre>
 * private final PromptSlot prompts = new PromptSlot();
 *
 * &#64;Autowired(required = false)
 * void setPromptResolver(PromptResolver resolver) { prompts.use(resolver); }
 *
 * String system = prompts.text("summarizer.compaction", agentId);
 * </pre>
 *
 * <p>{@link #text} never throws and never returns null for a registered key: if the winning override
 * switched the prompt off (only a {@code disableable} key can be) the call site decides with {@link
 * #resolve}; otherwise a lookup that yields nothing falls back to the shipped default of the key. An
 * unregistered key yields the empty string (and the resolver logs the defect once).
 */
public final class PromptSlot {

    private volatile PromptResolver resolver = PromptResolver.defaultsOnly();

    /** Uses {@code resolver}; null keeps the shipped defaults. */
    public void use(PromptResolver resolver) {
        this.resolver = resolver == null ? PromptResolver.defaultsOnly() : resolver;
    }

    public PromptResolver resolver() {
        return resolver;
    }

    /** The full resolution (override, scope, version) for {@code key}, for a call site that gates on it. */
    public ResolvedPrompt resolve(String key, UUID agentId, Map<String, String> variables) {
        PromptContext context = PromptContext.forAgent(agentId);
        if (variables != null && !variables.isEmpty()) {
            context = context.withVariables(variables);
        }
        return resolver.resolve(key, context);
    }

    /** The text to send for {@code key} for this agent (null = tenant-wide) with {@code variables} filled in. */
    public String text(String key, UUID agentId, Map<String, String> variables) {
        ResolvedPrompt resolved;
        try {
            resolved = resolve(key, agentId, variables);
        } catch (RuntimeException e) {
            resolved = null;
        }
        if (resolved != null && resolved.active()) {
            return resolved.text();
        }
        if (resolved != null && resolved.known() && !resolved.enabled()) {
            return "";
        }
        PromptResolver shipped = PromptResolver.defaultsOnly();
        PromptContext context = PromptContext.none();
        if (variables != null && !variables.isEmpty()) {
            context = context.withVariables(variables);
        }
        String fallback = shipped.resolve(key, context).textOrNull();
        return fallback == null ? "" : fallback;
    }

    /**
     * The entries of a list-valued key (a word or tool-name list a detector reads): one per line, each stripped, blank
     * lines and lines starting with {@code #} skipped. Empty when the key is unknown or switched off.
     */
    public List<String> lines(String key, UUID agentId) {
        return parseLines(text(key, agentId, Map.of()));
    }

    /**
     * As {@link #lines}, over every locale the catalog ships a text for: the default locale's entries as
     * resolved for {@code agentId} (so a tenant or agent override of the list applies), then the shipped
     * entries of each other locale, which are always applied. This is how a word list covers more than one
     * language: ship {@code prompts/<locale>/<key>.md} holding only the entries.
     */
    public List<String> linesAllLocales(String key, UUID agentId) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(lines(key, agentId));
        try {
            PromptCatalog catalog = resolver.catalog();
            for (String locale : catalog.locales(key)) {
                if (!locale.equals(catalog.defaultLocale())) {
                    catalog.defaultText(key, locale).ifPresent(text -> out.addAll(parseLines(text)));
                }
            }
        } catch (RuntimeException e) {
            // the default-locale entries stand
        }
        return new ArrayList<>(out);
    }

    /** The same parsing, for a text already in hand. */
    public static List<String> parseLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty() && !line.startsWith("#")) {
                out.add(line);
            }
        }
        return out;
    }

    public String text(String key, UUID agentId) {
        return text(key, agentId, Map.of());
    }

    public String text(String key) {
        return text(key, null, Map.of());
    }
}
