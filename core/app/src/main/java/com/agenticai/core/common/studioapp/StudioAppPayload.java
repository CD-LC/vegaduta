package com.agenticai.core.common.studioapp;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The no-code "contract" a studio builder configures on each connection: what the app's input
 * becomes on the way in, and which part of the result the app gets back.
 *
 * <p>Request template placeholders: {@code {{input}}} (the caller's text or whole payload as JSON),
 * {@code {{user.name}}}/{@code {{user.email}}} (the signed-in end user - empty when anonymous),
 * {@code {{data.<key>}}} (a field of the caller's {@code data} object). Anything unrecognized is
 * left untouched, never blanked.
 *
 * <p>Response path: a tiny dotted-path lookup ({@code reply}, {@code resultText}, {@code
 * data.items.0.name}) over a JSON response. Missing path falls back to the whole body text - a
 * misconfigured path degrades the answer, it never eats it.
 */
public final class StudioAppPayload {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_.-]+)\\s*}}");

    private StudioAppPayload() {}

    /**
     * Renders {@code template} against the call context; null/blank template returns {@code input}
     * verbatim.
     */
    public static String renderRequest(
            String template, String input, Map<String, Object> data, AppUserView user) {
        if (template == null || template.isBlank()) {
            return input == null ? "" : input;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value =
                    switch (key) {
                        case "input" -> input == null ? "" : input;
                        case "user.name" ->
                                user != null && user.displayName() != null
                                        ? user.displayName()
                                        : "";
                        case "user.email" ->
                                user != null && user.email() != null ? user.email() : "";
                        case "user.id" -> user != null ? user.id() : "";
                        default ->
                                key.startsWith("data.")
                                        ? dataValue(data, key.substring(5))
                                        : m.group(0);
                    };
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The app-facing text out of a backend response: {@code path} into the JSON if it parses, else
     * the raw text.
     */
    public static String extractResponse(String path, String defaultField, String body) {
        if (body == null) {
            return "";
        }
        String effective = (path == null || path.isBlank()) ? defaultField : path.trim();
        if (effective.isBlank()) {
            return body;
        }
        Optional<JsonNode> node = parse(body).flatMap(root -> lookup(root, effective));
        return node.map(n -> n.isTextual() ? n.asText() : n.toString()).orElse(body);
    }

    /** Extracts {@code path} from an already-parsed JSON tree; empty when absent. */
    public static Optional<JsonNode> extract(JsonNode root, String path) {
        return lookup(root, path);
    }

    public record AppUserView(String id, String email, String displayName) {}

    // ------------------------------------------------------------------

    private static String dataValue(Map<String, Object> data, String key) {
        if (data == null) {
            return "";
        }
        Object v = data.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static Optional<JsonNode> parse(String body) {
        try {
            JsonNode node = JsonMappers.plain().readTree(body);
            return Optional.ofNullable(node);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Dotted path with optional numeric segments for arrays: {@code a.b.0.c}. */
    private static Optional<JsonNode> lookup(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return Optional.empty();
        }
        JsonNode node = root;
        for (String seg : path.split("\\.")) {
            if (node == null) {
                return Optional.empty();
            }
            if (node.isArray() && seg.matches("\\d+")) {
                node = node.get(Integer.parseInt(seg));
            } else if (node.isObject()) {
                node = node.get(seg);
            } else {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(node);
    }
}
