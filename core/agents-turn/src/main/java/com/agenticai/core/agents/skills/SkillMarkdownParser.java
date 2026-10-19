package com.agenticai.core.agents.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses and validates a SKILL.md: YAML frontmatter ({@code name}, {@code description}) followed by
 * a markdown body. The field rules are the published Agent Skills ones, kept identical so a skill
 * written for any other Agent Skills host imports here unchanged:
 *
 * <ul>
 *   <li>{@code name}: required, max 64 chars, lowercase letters / digits / hyphens only, no XML
 *       tags, no reserved words.
 *   <li>{@code description}: required, non-empty, max 1024 chars, no XML tags.
 * </ul>
 *
 * <p>The "no XML tags" rule is not cosmetic. Name and description are injected verbatim into the
 * system prompt of every agent in the workspace (Level 1), so a tag there is the cheapest possible
 * prompt-injection vector - it has to be refused at write time, not escaped at read time.
 *
 * <p>Stateless and static so it is unit-testable without a Spring context.
 */
public final class SkillMarkdownParser {

    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_DESCRIPTION_LENGTH = 1024;

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern XML_TAG = Pattern.compile("<[^>]*>");

    /**
     * The spec's reserved words, plus this platform's own brand so a tenant skill cannot pose as a
     * platform one.
     */
    private static final List<String> RESERVED_WORDS = List.of("anthropic", "claude", "vegaduta");

    private static final String FENCE = "---";

    private SkillMarkdownParser() {}

    /** name + description + body, all validated. */
    public record Parsed(String name, String description, String body) {}

    public static Parsed parse(String skillMd) {
        String[] parts = splitFrontmatter(skillMd);
        String frontmatter = parts[0];
        String body = parts[1];

        Map<String, String> fields = readFrontmatter(frontmatter);
        String name = validateName(fields.get("name"));
        String description = validateDescription(fields.get("description"));
        if (body.isEmpty()) {
            throw new SkillValidationException(
                    "SKILL.md has no instructions after the frontmatter.");
        }
        return new Parsed(name, description, body);
    }

    /** {frontmatter text, stripped body}; throws the person-facing errors for a malformed file. */
    private static String[] splitFrontmatter(String skillMd) {
        if (skillMd == null || skillMd.isBlank()) {
            throw new SkillValidationException("SKILL.md is empty.");
        }
        // Tolerate a UTF-8 BOM and CRLF - both are what a Windows editor produces.
        String text = skillMd.replace("\r\n", "\n").replace('\r', '\n');
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        String trimmedStart = text.stripLeading();
        if (!trimmedStart.startsWith(FENCE)) {
            throw new SkillValidationException(
                    "SKILL.md must start with YAML frontmatter: a line of --- then name: and description: then another ---.");
        }
        int firstLineEnd = trimmedStart.indexOf('\n');
        if (firstLineEnd < 0 || !trimmedStart.substring(0, firstLineEnd).trim().equals(FENCE)) {
            throw new SkillValidationException(
                    "SKILL.md frontmatter must open with a line containing only ---.");
        }
        int close = findClosingFence(trimmedStart, firstLineEnd + 1);
        if (close < 0) {
            throw new SkillValidationException(
                    "SKILL.md frontmatter is never closed - add a line containing only ---.");
        }
        String frontmatter = trimmedStart.substring(firstLineEnd + 1, close);
        int bodyStart = trimmedStart.indexOf('\n', close);
        String body = bodyStart < 0 ? "" : trimmedStart.substring(bodyStart + 1).strip();
        return new String[] {frontmatter, body};
    }

    /** Renders a canonical SKILL.md - used for export and for the editor round-trip. */
    public static String render(String name, String description, String body) {
        // Description is double-quoted so a colon or '#' inside it can never change the YAML's
        // meaning.
        String escaped = description.replace("\\", "\\\\").replace("\"", "\\\"");
        return FENCE
                + "\nname: "
                + name
                + "\ndescription: \""
                + escaped
                + "\"\n"
                + FENCE
                + "\n\n"
                + body.strip()
                + "\n";
    }

    // ------------------------------------------------------------------ optional frontmatter keys

    public static final int MAX_LICENSE_LENGTH = 255;
    public static final int MAX_COMPATIBILITY_LENGTH = 500;
    public static final int MAX_ALLOWED_TOOLS_LENGTH = 1000;
    public static final int MAX_METADATA_ENTRIES = 20;
    public static final int MAX_METADATA_TEXT_LENGTH = 256;

    /** The optional keys of the Agent Skills spec; anything else in the frontmatter is dropped. */
    private static final List<String> EXTRA_KEYS =
            List.of("license", "compatibility", "metadata", "allowed-tools");

    /**
     * The optional frontmatter keys ({@code license}, {@code compatibility}, {@code metadata},
     * {@code allowed-tools}) of a SKILL.md, validated, in spec order. Empty when there are none or
     * the frontmatter is not strict YAML (the lenient reader only understands name/description).
     * They are kept so an imported skill exports unchanged; they are informational only, and in
     * particular {@code allowed-tools} never grants an agent a tool.
     */
    public static Map<String, Object> parseExtras(String skillMd) {
        String frontmatter = splitFrontmatter(skillMd)[0];
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(frontmatter);
        } catch (RuntimeException e) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        map.forEach(
                (k, v) -> {
                    if (k != null && v != null) {
                        raw.put(k.toString().trim().toLowerCase(java.util.Locale.ROOT), v);
                    }
                });
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : EXTRA_KEYS) {
            Object value = raw.get(key);
            if (value == null) {
                continue;
            }
            switch (key) {
                case "license" -> out.put(key, extraText(key, value, MAX_LICENSE_LENGTH));
                case "compatibility" ->
                        out.put(key, extraText(key, value, MAX_COMPATIBILITY_LENGTH));
                case "allowed-tools" -> {
                    String joined =
                            value instanceof List<?> list
                                    ? list.stream()
                                            .map(String::valueOf)
                                            .collect(java.util.stream.Collectors.joining(" "))
                                    : value.toString();
                    out.put(key, extraText(key, joined, MAX_ALLOWED_TOOLS_LENGTH));
                }
                default -> out.put(key, metadataOf(value));
            }
        }
        return out;
    }

    private static String extraText(String key, Object value, int max) {
        if (value instanceof Map || value instanceof List) {
            throw new SkillValidationException("SKILL.md \"" + key + "\" must be plain text.");
        }
        String text = value.toString().replaceAll("\\s+", " ").strip();
        if (text.length() > max) {
            throw new SkillValidationException(
                    "SKILL.md \"" + key + "\" is too long - the maximum is " + max + " characters.");
        }
        if (XML_TAG.matcher(text).find()) {
            throw new SkillValidationException(
                    "SKILL.md \"" + key + "\" cannot contain XML or HTML tags.");
        }
        return text;
    }

    private static Map<String, String> metadataOf(Object value) {
        if (!(value instanceof Map<?, ?> entries)) {
            throw new SkillValidationException("SKILL.md \"metadata\" must be a map of text values.");
        }
        if (entries.size() > MAX_METADATA_ENTRIES) {
            throw new SkillValidationException(
                    "SKILL.md \"metadata\" has too many entries - the maximum is "
                            + MAX_METADATA_ENTRIES
                            + ".");
        }
        Map<String, String> out = new LinkedHashMap<>();
        entries.forEach(
                (k, v) -> {
                    String key =
                            extraText("metadata key", String.valueOf(k), MAX_METADATA_TEXT_LENGTH);
                    out.put(
                            key,
                            v == null
                                    ? ""
                                    : extraText("metadata." + key, v, MAX_METADATA_TEXT_LENGTH));
                });
        return out;
    }

    /** Extras as the JSON text stored in {@code skill.frontmatter_extra}; null when there are none. */
    public static String extrasToJson(Map<String, Object> extras) {
        if (extras == null || extras.isEmpty()) {
            return null;
        }
        try {
            return com.agenticai.core.json.JsonMappers.plain().writeValueAsString(extras);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SkillValidationException("The skill's optional metadata could not be saved.");
        }
    }

    /** Inverse of {@link #extrasToJson}; unreadable or absent text yields no extras. */
    public static Map<String, Object> extrasFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return com.agenticai.core.json.JsonMappers.plain()
                    .readValue(
                            json,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    LinkedHashMap<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of();
        }
    }

    /** {@link #render(String, String, String)} plus the optional keys, each double-quoted. */
    public static String render(
            String name, String description, String body, Map<String, Object> extras) {
        String base = render(name, description, body);
        if (extras == null || extras.isEmpty()) {
            return base;
        }
        StringBuilder block = new StringBuilder();
        for (String key : EXTRA_KEYS) {
            Object value = extras.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> entries) {
                if (!entries.isEmpty()) {
                    block.append(key).append(":\n");
                    entries.forEach(
                            (k, v) ->
                                    block.append("  ")
                                            .append(quoted(String.valueOf(k)))
                                            .append(": ")
                                            .append(quoted(String.valueOf(v)))
                                            .append('\n'));
                }
            } else {
                block.append(key).append(": ").append(quoted(value.toString())).append('\n');
            }
        }
        // Slot the keys in right before the frontmatter's closing fence.
        int close = base.indexOf("\n" + FENCE + "\n", FENCE.length());
        return close < 0 ? base : base.substring(0, close + 1) + block + base.substring(close + 1);
    }

    private static String quoted(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return "\"" + line.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public static String validateName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new SkillValidationException("A skill needs a name.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new SkillValidationException(
                    "Skill name is too long - the maximum is " + MAX_NAME_LENGTH + " characters.");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new SkillValidationException(
                    "Skill name may only contain lowercase letters, numbers and hyphens (for example: quarterly-report).");
        }
        for (String reserved : RESERVED_WORDS) {
            if (name.contains(reserved)) {
                throw new SkillValidationException(
                        "Skill name cannot contain the reserved word \"" + reserved + "\".");
            }
        }
        return name;
    }

    public static String validateDescription(String raw) {
        String description = raw == null ? "" : raw.trim();
        if (description.isEmpty()) {
            throw new SkillValidationException(
                    "A skill needs a description - say what it does AND when to use it, because that is all the agent sees until it loads the skill.");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new SkillValidationException(
                    "Skill description is too long - the maximum is "
                            + MAX_DESCRIPTION_LENGTH
                            + " characters.");
        }
        if (XML_TAG.matcher(description).find()) {
            throw new SkillValidationException(
                    "Skill description cannot contain XML or HTML tags.");
        }
        return description;
    }

    private static int findClosingFence(String text, int from) {
        int pos = from;
        while (pos < text.length()) {
            int lineEnd = text.indexOf('\n', pos);
            String line = lineEnd < 0 ? text.substring(pos) : text.substring(pos, lineEnd);
            if (line.trim().equals(FENCE)) {
                return pos;
            }
            if (lineEnd < 0) {
                break;
            }
            pos = lineEnd + 1;
        }
        return -1;
    }

    /**
     * Real YAML first. Hand-written frontmatter very often has an unquoted colon in the description
     * ("Use when: ..."), which is invalid YAML but unambiguous to a person - so on a YAML failure
     * fall back to a plain "key: rest of line" read rather than rejecting a skill every other host
     * accepts. SafeConstructor: frontmatter is tenant-supplied, never instantiate arbitrary types.
     */
    private static Map<String, String> readFrontmatter(String frontmatter) {
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(frontmatter);
            if (loaded instanceof Map<?, ?> map) {
                Map<String, String> out = new LinkedHashMap<>();
                map.forEach(
                        (k, v) -> {
                            if (k != null
                                    && v != null
                                    && !(v instanceof Map)
                                    && !(v instanceof List)) {
                                out.put(
                                        k.toString().trim().toLowerCase(java.util.Locale.ROOT),
                                        v.toString());
                            }
                        });
                if (out.containsKey("name") && out.containsKey("description")) {
                    return out;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the lenient reader
        }
        Map<String, String> out = new LinkedHashMap<>();
        String currentKey = null;
        for (String line : frontmatter.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            boolean continuation = Character.isWhitespace(line.charAt(0));
            if (!continuation && colon > 0) {
                currentKey = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                out.put(currentKey, unquote(line.substring(colon + 1).trim()));
            } else if (continuation && currentKey != null) {
                out.merge(
                        currentKey,
                        line.trim(),
                        (a, b) -> a.isEmpty() || a.equals(">") || a.equals("|") ? b : a + " " + b);
            }
        }
        return out;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
