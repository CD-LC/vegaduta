package com.agenticai.core.prompts.tool;

import com.agenticai.core.prompts.PromptCatalog;
import com.agenticai.core.prompts.PromptDefinition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which tool names and parameters a catalog has keys for, built once per catalog. A tool name maps
 * to its ids (the plain name first, then {@code name.variant} ids); an id maps to the {@code
 * tool.param.<id>.*} keys that belong to it (a key belongs to the longest id that prefixes it, so
 * the parameters of {@code http_request.safe} are not mistaken for parameters of {@code
 * http_request}).
 */
final class ToolPromptIndex {

    private static final Map<PromptCatalog, ToolPromptIndex> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /** The placeholder syntax of the registry ({@code {name}}), see PromptTemplates. */
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    final Map<String, List<String>> idsByName = new HashMap<>();
    final Map<String, List<String>> paramKeysById = new HashMap<>();
    private final Map<String, Template> templates = new ConcurrentHashMap<>();

    private ToolPromptIndex() {}

    /**
     * The id whose {@code tool.param.*} keys describe the parameters of {@code id}: the id itself,
     * or, for a variant that ships none of its own, the plain tool name (variants such as
     * {@code local_shell.unattended} differ in the description only).
     */
    String paramOwner(String id) {
        return paramKeysById.containsKey(id) ? id : ToolPromptKeys.toolName(id);
    }

    /** A shipped description with placeholders, as a pattern that recovers the values from rendered text. */
    record Template(Pattern pattern, List<String> names) {

        /** The placeholder values if {@code rendered} is this template filled in; null if it is not. */
        Map<String, String> match(String rendered) {
            if (rendered == null) {
                return null;
            }
            Matcher m = pattern.matcher(rendered);
            if (!m.matches()) {
                return null;
            }
            Map<String, String> values = new HashMap<>();
            for (int i = 0; i < names.size(); i++) {
                String value = m.group(i + 1);
                String earlier = values.put(names.get(i), value);
                if (earlier != null && !earlier.equals(value)) {
                    return null;
                }
            }
            return values;
        }
    }

    /** The template of {@code key}'s shipped text; null when the key declares no placeholder that its text uses. */
    Template template(PromptCatalog catalog, String key) {
        Template cached = templates.get(key);
        if (cached != null) {
            return cached;
        }
        PromptDefinition def = catalog.find(key).orElse(null);
        String text = catalog.defaultText(key, catalog.defaultLocale()).orElse(null);
        if (def == null || text == null || def.placeholders().isEmpty()) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        List<String> names = new ArrayList<>();
        Matcher token = TOKEN.matcher(text);
        int last = 0;
        while (token.find()) {
            if (!def.placeholderNames().contains(token.group(1))) {
                continue;
            }
            regex.append(Pattern.quote(text.substring(last, token.start())));
            regex.append("(.*?)");
            names.add(token.group(1));
            last = token.end();
        }
        regex.append(Pattern.quote(text.substring(last)));
        Template t = new Template(Pattern.compile(regex.toString(), Pattern.DOTALL), names);
        templates.put(key, t);
        return t;
    }

    static ToolPromptIndex of(PromptCatalog catalog) {
        return CACHE.computeIfAbsent(catalog, ToolPromptIndex::build);
    }

    private static ToolPromptIndex build(PromptCatalog catalog) {
        ToolPromptIndex idx = new ToolPromptIndex();
        List<String> ids = new ArrayList<>();
        List<String> ownerIds = new ArrayList<>();
        for (PromptDefinition def : catalog.all()) {
            if (def.key().startsWith(ToolPromptKeys.DESCRIPTION_PREFIX)) {
                String id = def.key().substring(ToolPromptKeys.DESCRIPTION_PREFIX.length());
                if (ToolPromptKeys.isValidId(id)) {
                    ids.add(id);
                    ownerIds.add(id);
                }
            } else if (def.key().startsWith(ToolPromptKeys.TEMPLATE_PREFIX)) {
                String id = def.key().substring(ToolPromptKeys.TEMPLATE_PREFIX.length());
                if (ToolPromptKeys.isValidId(id)) {
                    ownerIds.add(id);
                }
            }
        }
        // The plain tool name first, then its variants.
        ids.sort((a, b) -> a.contains(".") == b.contains(".") ? a.compareTo(b) : a.contains(".") ? 1 : -1);
        for (String id : ids) {
            idx.idsByName.computeIfAbsent(ToolPromptKeys.toolName(id), k -> new ArrayList<>()).add(id);
        }
        for (PromptDefinition def : catalog.all()) {
            String key = def.key();
            if (!key.startsWith(ToolPromptKeys.PARAM_PREFIX)) {
                continue;
            }
            String owner = null;
            for (String id : ownerIds) {
                if (key.startsWith(ToolPromptKeys.PARAM_PREFIX + id + ".")
                        && (owner == null || id.length() > owner.length())) {
                    owner = id;
                }
            }
            if (owner != null) {
                idx.paramKeysById.computeIfAbsent(owner, k -> new ArrayList<>()).add(key);
            }
        }
        return idx;
    }
}
