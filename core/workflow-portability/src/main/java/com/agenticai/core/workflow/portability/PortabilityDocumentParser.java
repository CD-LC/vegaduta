package com.agenticai.core.workflow.portability;

import com.agenticai.core.json.JsonMappers;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns the text of an uploaded workflow file into a JSON tree, whatever it is written in: JSON
 * (VegaDuta DSL, n8n) is read with Jackson under nesting/string limits, YAML (Dify) goes through
 * {@link SafeYamlLoader}.
 *
 * <p>The text is DATA. Nothing here fetches a URL, follows a reference outside the document or
 * evaluates a value. Error messages never quote the document.
 */
public final class PortabilityDocumentParser {

    /** Nesting depth the JSON reader accepts; real n8n/Dify exports are well under 30 deep. */
    public static final int MAX_NESTING_DEPTH = 64;

    private final ObjectMapper json;
    private final int maxChars;

    public PortabilityDocumentParser(long maxPayloadBytes) {
        long cap = Math.max(1024L, maxPayloadBytes);
        this.maxChars = (int) Math.min(Integer.MAX_VALUE - 8L, cap);
        JsonFactory factory =
                JsonFactory.builder()
                        .streamReadConstraints(
                                StreamReadConstraints.builder()
                                        .maxNestingDepth(MAX_NESTING_DEPTH)
                                        .maxStringLength(maxChars)
                                        .maxNumberLength(64)
                                        .build())
                        .build();
        this.json = JsonMappers.withFactory(factory);
    }

    /**
     * A parsed document. {@code root} is the single workflow object; {@code dropped} counts extra
     * top-level entries ignored.
     */
    public record Parsed(JsonNode root, boolean fromYaml, int dropped) {}

    /**
     * @throws IllegalArgumentException when the text is empty, not JSON/YAML, over a safety limit,
     *     or not a single workflow object
     */
    public Parsed parse(String text) {
        String t = text == null ? "" : text;
        if (!t.isEmpty() && t.charAt(0) == '\uFEFF') {
            t = t.substring(1);
        }
        t = t.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("The workflow file is empty.");
        }
        JsonNode root = null;
        boolean yaml = false;
        char first = t.charAt(0);
        if (first == '{' || first == '[') {
            try {
                root = json.readTree(t);
            } catch (JacksonException e) {
                // A YAML flow mapping ({a: 1}) starts with a brace too; fall through to YAML before
                // giving up.
                root = null;
            }
        }
        if (root == null) {
            yaml = true;
            try {
                root = SafeYamlLoader.load(t, maxChars);
            } catch (IllegalArgumentException e) {
                if (first == '{' || first == '[') {
                    throw new IllegalArgumentException(
                            "The file is not valid JSON or YAML, or is nested more than "
                                    + MAX_NESTING_DEPTH
                                    + " levels deep.");
                }
                throw e;
            }
        }
        int dropped = 0;
        if (root != null && root.isArray()) {
            // n8n's CLI exports an ARRAY of workflows; take the first and say that the rest were
            // not imported.
            if (root.isEmpty() || !root.get(0).isObject()) {
                throw new IllegalArgumentException(
                        "The file is a list with no workflow object in it.");
            }
            dropped = root.size() - 1;
            root = root.get(0);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    "The workflow file must contain a JSON or YAML object.");
        }
        return new Parsed(root, yaml, dropped);
    }
}
