package com.agenticai.core.prompts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {name}} placeholder syntax: finding tokens, validating a text against its {@link
 * PromptDefinition}, substituting values. A token is {@code {identifier}} (letters, digits,
 * underscore); anything else in braces ({@code {"a":1}}, {@code {{secret.NAME}}}) is plain text.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** The distinct token names in {@code text}, in order of first appearance. */
    public static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Why {@code text} may not be saved as the wording of {@code definition}; empty = valid. Checks
     * unknown placeholders, missing required placeholders and the length bounds. Blank text is not
     * checked here (the caller treats it as "no override").
     */
    public static List<String> validate(PromptDefinition definition, String text) {
        List<String> problems = new ArrayList<>();
        if (text == null) {
            return problems;
        }
        Set<String> declared = definition.placeholderNames();
        for (String token : tokens(text)) {
            if (definition.strictPlaceholders() && !declared.contains(token)) {
                problems.add(
                        "Unknown placeholder {"
                                + token
                                + "}; "
                                + (declared.isEmpty()
                                        ? "this prompt has no placeholders."
                                        : "allowed: " + String.join(", ", declared.stream().map(n -> "{" + n + "}").sorted().toList()) + "."));
            }
        }
        Set<String> present = tokens(text);
        for (String required : definition.requiredPlaceholderNames()) {
            if (!present.contains(required)) {
                problems.add("Missing required placeholder {" + required + "}.");
            }
        }
        int length = text.length();
        if (definition.maxLength() > 0 && length > definition.maxLength()) {
            problems.add("Text is limited to " + definition.maxLength() + " characters (it has " + length + ").");
        }
        if (definition.minLength() > 0 && length < definition.minLength()) {
            problems.add("Text needs at least " + definition.minLength() + " characters (it has " + length + ").");
        }
        return problems;
    }

    /**
     * Replaces each declared token that has a value in {@code variables}; every other token is left
     * as written (a missing value must not break a turn, and an undeclared token is plain text).
     */
    public static String render(String text, Set<String> declared, Map<String, String> variables) {
        if (text == null || declared.isEmpty() || variables == null || variables.isEmpty()) {
            return text;
        }
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            String name = m.group(1);
            String value = declared.contains(name) ? variables.get(name) : null;
            if (value == null) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(text.length() + 16);
            }
            out.append(text, last, m.start()).append(value);
            last = m.end();
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }
}
