package com.agenticai.core.prompts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Every registered prompt and its default text, read from {@code classpath*:prompts/<locale>/<key>.md}.
 * Any module contributes keys just by shipping files under that path in its own {@code
 * src/main/resources}.
 *
 * <p>File format: the file of the default locale starts with a front-matter block (the definition),
 * the body after it is the text. The file of another locale holds only the body (a front-matter
 * block there is ignored). The body is taken byte for byte, except that CRLF becomes LF and one
 * final line break is dropped (so a file may end with a newline while the text does not).
 *
 * <pre>
 * ---
 * title: Tool search
 * description: When some tools are withheld to save context ...
 * risk: STEERS_EVERY_TURN
 * securityCritical: false
 * disableable: true
 * retired: false
 * strictPlaceholders: true
 * maxLength: 4000
 * minLength: 0
 * placeholders: date!, other
 * placeholder.date: Today's date, UTC, ISO-8601
 * ---
 * Text of the prompt ...
 * </pre>
 *
 * <p>{@code risk} is one of {@link PromptRiskClass}; a placeholder name ending in {@code !} is
 * required. {@code title} and {@code risk} are mandatory, every other field is optional.
 *
 * <p>A broken file never stops the application: it is skipped, logged, and listed in {@link
 * #problems()}; the catalog test of each module asserts that list is empty.
 */
public final class PromptCatalog {

    private static final Logger log = LoggerFactory.getLogger(PromptCatalog.class);

    static final String PATTERN = "classpath*:prompts/*/*.md";
    private static final String FENCE = "---";

    private final String defaultLocale;
    private final Map<String, PromptDefinition> definitions;
    private final Map<String, Map<String, String>> texts;
    private final List<String> problems;

    private PromptCatalog(
            String defaultLocale,
            Map<String, PromptDefinition> definitions,
            Map<String, Map<String, String>> texts,
            List<String> problems) {
        this.defaultLocale = defaultLocale;
        this.definitions = definitions;
        this.texts = texts;
        this.problems = problems;
    }

    /** Scans the classpath of {@code classLoader}. */
    public static PromptCatalog load(ClassLoader classLoader, String defaultLocale, int defaultMaxLength) {
        Map<String, String> sources = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        try {
            Resource[] found = new PathMatchingResourcePatternResolver(classLoader).getResources(PATTERN);
            for (Resource r : found) {
                String path = r.getURL().toString();
                int at = path.lastIndexOf("prompts/");
                String relative = at < 0 ? path : path.substring(at);
                try (InputStream in = r.getInputStream()) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    if (sources.putIfAbsent(relative, content) != null) {
                        problems.add(relative + ": defined in more than one jar/directory (the first one wins)");
                    }
                }
            }
        } catch (IOException e) {
            problems.add("could not scan " + PATTERN + ": " + e);
        }
        PromptCatalog catalog = fromSources(sources, defaultLocale, defaultMaxLength);
        if (problems.isEmpty()) {
            return catalog;
        }
        List<String> all = new ArrayList<>(problems);
        all.addAll(catalog.problems);
        all.forEach(p -> log.error("Prompt catalog: {}", p));
        return new PromptCatalog(catalog.defaultLocale, catalog.definitions, catalog.texts, Collections.unmodifiableList(all));
    }

    /**
     * Builds a catalog from file contents keyed by classpath-relative path ({@code
     * prompts/en/directive.tool_search.md}); the seam of {@link #load} and of tests.
     */
    public static PromptCatalog fromSources(Map<String, String> sources, String defaultLocale, int defaultMaxLength) {
        Map<String, PromptDefinition> definitions = new TreeMap<>();
        Map<String, Map<String, String>> texts = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        Map<String, Map<String, String>> pendingOtherLocales = new TreeMap<>();
        for (Map.Entry<String, String> source : new TreeMap<>(sources).entrySet()) {
            String path = source.getKey();
            String[] parts = path.split("/");
            if (parts.length != 3 || !parts[0].equals("prompts") || !parts[2].endsWith(".md")) {
                problems.add(path + ": expected prompts/<locale>/<key>.md");
                continue;
            }
            String locale = parts[1];
            String key = parts[2].substring(0, parts[2].length() - ".md".length());
            if (!PromptKeys.isValid(key)) {
                problems.add(path + ": '" + key + "' is not a valid prompt key");
                continue;
            }
            String content = normalize(source.getValue());
            if (locale.equalsIgnoreCase(defaultLocale)) {
                try {
                    Parsed parsed = parse(content, key, defaultMaxLength);
                    if (definitions.containsKey(key)) {
                        problems.add(path + ": key already defined");
                        continue;
                    }
                    definitions.put(key, parsed.definition());
                    texts.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(locale, parsed.body());
                } catch (IllegalArgumentException e) {
                    problems.add(path + ": " + e.getMessage());
                }
            } else {
                pendingOtherLocales.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(locale, bodyOf(content));
            }
        }
        for (Map.Entry<String, PromptDefinition> d : definitions.entrySet()) {
            PromptDefinition def = d.getValue();
            String body = texts.get(d.getKey()).get(defaultLocale);
            if (def.retired() && !body.isEmpty()) {
                problems.add(d.getKey() + ": a retired prompt must have an empty text");
            }
            if (!body.isBlank()) {
                for (String p : PromptTemplates.validate(def, body)) {
                    problems.add(d.getKey() + " (" + defaultLocale + "): " + p);
                }
            }
        }
        for (Map.Entry<String, Map<String, String>> e : pendingOtherLocales.entrySet()) {
            PromptDefinition def = definitions.get(e.getKey());
            if (def == null) {
                problems.add(e.getKey() + ": has translations but no '" + defaultLocale + "' file");
                continue;
            }
            for (Map.Entry<String, String> t : e.getValue().entrySet()) {
                List<String> bad = PromptTemplates.validate(def, t.getValue());
                if (bad.isEmpty() || t.getValue().isBlank()) {
                    texts.get(e.getKey()).put(t.getKey(), t.getValue());
                } else {
                    for (String p : bad) {
                        problems.add(e.getKey() + " (" + t.getKey() + "): " + p);
                    }
                }
            }
        }
        problems.forEach(p -> log.error("Prompt catalog: {}", p));
        Map<String, Map<String, String>> frozen = new TreeMap<>();
        texts.forEach((k, v) -> frozen.put(k, Collections.unmodifiableMap(v)));
        return new PromptCatalog(
                defaultLocale,
                Collections.unmodifiableMap(definitions),
                Collections.unmodifiableMap(frozen),
                Collections.unmodifiableList(problems));
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    /** What is wrong with the shipped files; empty when the catalog is sound. */
    public List<String> problems() {
        return problems;
    }

    public Optional<PromptDefinition> find(String key) {
        return Optional.ofNullable(definitions.get(key));
    }

    /** All definitions, sorted by key. */
    public Collection<PromptDefinition> all() {
        return definitions.values();
    }

    /** The locales a key ships a text for. */
    public Set<String> locales(String key) {
        Map<String, String> byLocale = texts.get(key);
        return byLocale == null ? Set.of() : byLocale.keySet();
    }

    /**
     * The default text of {@code key} in {@code locale}, else in the default locale; empty for an
     * unknown key.
     */
    public Optional<String> defaultText(String key, String locale) {
        Map<String, String> byLocale = texts.get(key);
        if (byLocale == null) {
            return Optional.empty();
        }
        String exact = locale == null ? null : byLocale.get(locale);
        return Optional.ofNullable(exact != null ? exact : byLocale.get(defaultLocale));
    }

    /** The default text in the default locale; throws for an unknown key (a code defect). */
    public String defaultText(String key) {
        return defaultText(key, defaultLocale)
                .orElseThrow(() -> new IllegalArgumentException("Unknown prompt key: " + key));
    }

    // ------------------------------------------------------------------------------------------

    private record Parsed(PromptDefinition definition, String body) {}

    private static String normalize(String content) {
        String s = content.startsWith("﻿") ? content.substring(1) : content;
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** The text after an optional front-matter block, minus one final line break. */
    private static String bodyOf(String content) {
        String body = content;
        if (content.startsWith(FENCE + "\n")) {
            int close = content.indexOf("\n" + FENCE + "\n", FENCE.length());
            if (close >= 0) {
                body = content.substring(close + FENCE.length() + 2);
            } else if (content.endsWith("\n" + FENCE)) {
                body = "";
            }
        }
        return body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
    }

    private static Parsed parse(String content, String key, int defaultMaxLength) {
        if (!content.startsWith(FENCE + "\n")) {
            throw new IllegalArgumentException("the default-locale file must start with a '---' front-matter block");
        }
        int close = content.indexOf("\n" + FENCE + "\n", FENCE.length());
        String header;
        if (close >= 0) {
            header = close <= FENCE.length() ? "" : content.substring(FENCE.length() + 1, close);
        } else if (content.endsWith("\n" + FENCE)) {
            header = content.substring(FENCE.length() + 1, content.length() - FENCE.length() - 1);
        } else {
            throw new IllegalArgumentException("front matter is not closed by a '---' line");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : header.split("\n")) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int colon = stripped.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("bad front-matter line: " + line);
            }
            String name = stripped.substring(0, colon).strip();
            String value = stripped.substring(colon + 1).strip();
            if (fields.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("front-matter field repeated: " + name);
            }
        }
        String title = fields.get("title");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("front matter needs a title");
        }
        PromptRiskClass risk;
        try {
            risk = PromptRiskClass.valueOf(fields.getOrDefault("risk", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("front matter needs risk: one of " + List.of(PromptRiskClass.values()));
        }
        List<PromptPlaceholder> placeholders = new ArrayList<>();
        String declared = fields.getOrDefault("placeholders", "");
        for (String raw : declared.split(",")) {
            String name = raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            boolean required = name.endsWith("!");
            if (required) {
                name = name.substring(0, name.length() - 1).strip();
            }
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("bad placeholder name: " + raw);
            }
            placeholders.add(new PromptPlaceholder(name, fields.getOrDefault("placeholder." + name, ""), required));
        }
        int max = intField(fields, "maxLength", defaultMaxLength);
        int min = intField(fields, "minLength", 0);
        PromptDefinition def =
                new PromptDefinition(
                        key,
                        title,
                        fields.getOrDefault("description", ""),
                        risk,
                        boolField(fields, "securityCritical"),
                        boolField(fields, "disableable"),
                        boolField(fields, "retired"),
                        boolField(fields, "strictPlaceholders", true),
                        placeholders,
                        min,
                        max);
        return new Parsed(def, bodyOf(content));
    }

    private static boolean boolField(Map<String, String> fields, String name) {
        return boolField(fields, name, false);
    }

    private static boolean boolField(Map<String, String> fields, String name, boolean fallback) {
        String v = fields.get(name);
        if (v == null) {
            return fallback;
        }
        if (!v.equals("true") && !v.equals("false")) {
            throw new IllegalArgumentException(name + " must be true or false");
        }
        return Boolean.parseBoolean(v);
    }

    private static int intField(Map<String, String> fields, String name, int fallback) {
        String v = fields.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number");
        }
    }
}
