package com.agenticai.core.workflow.portability;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.representer.Representer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads untrusted YAML (a Dify DSL file) into a Jackson tree without ever constructing an
 * application type.
 *
 * <p>Guards, in the order they matter:
 *
 * <ul>
 *   <li>{@link SafeConstructor}: only the standard YAML scalar/collection tags exist, so a {@code
 *       !!java.*} or {@code !!python/*} tag is refused instead of instantiating a class.
 *   <li>{@link LoaderOptions}: a code point cap (the input size), a nesting depth cap, a very small
 *       alias budget for collections (the "billion laughs" shape is a few anchors that each repeat
 *       the previous one), no recursive keys and no duplicate keys. The SAME options object is
 *       given to the constructor and to the {@code Yaml} itself - SnakeYAML's composer reads its
 *       limits from the latter.
 *   <li>A node budget while converting to a tree: aliases share one object in SnakeYAML's graph, so
 *       a document that stays inside the alias cap can still expand when walked; the walk stops at
 *       {@link #MAX_TREE_NODES} and at {@link #MAX_DEPTH} (which also ends a self-referencing
 *       anchor).
 * </ul>
 *
 * No SnakeYAML message is echoed to the caller: they can quote a fragment of the payload.
 */
final class SafeYamlLoader {

    static final int MAX_DEPTH = 64;
    static final int MAX_ALIASES = 10;
    static final int MAX_TREE_NODES = 200_000;

    private SafeYamlLoader() {}

    /**
     * @param maxCodePoints longest accepted input, in code points
     * @return the document as a tree; {@code NullNode} for an empty document
     * @throws IllegalArgumentException with a payload-free message when the text is not safe, valid
     *     YAML
     */
    static JsonNode load(String text, int maxCodePoints) {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(Math.max(1024, maxCodePoints));
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setAllowRecursiveKeys(false);
        options.setAllowDuplicateKeys(false);
        options.setProcessComments(false);
        Object loaded;
        try {
            DumperOptions dumper = new DumperOptions();
            Yaml yaml =
                    new Yaml(
                            new SafeConstructor(options), new Representer(dumper), dumper, options);
            loaded = yaml.load(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(describe(e));
        }
        int[] budget = {MAX_TREE_NODES};
        return convert(loaded, 0, budget);
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("constructor for the tag") || message.contains("tag:yaml.org,2002:")) {
            return "The YAML uses a custom type tag (such as !!java or !!python), which is not allowed. "
                    + "Workflow files are plain data.";
        }
        if (message.contains("aliases") || message.contains("alias")) {
            return "The YAML uses too many anchors/aliases (limit "
                    + MAX_ALIASES
                    + ") and was refused.";
        }
        if (message.contains("code point")
                || message.contains("limit")
                || message.contains("Nesting")) {
            return "The YAML is larger or more deeply nested than the import limits allow.";
        }
        if (e instanceof MarkedYAMLException marked && marked.getProblemMark() != null) {
            return "The text is not valid YAML (problem near line "
                    + (marked.getProblemMark().getLine() + 1)
                    + ").";
        }
        return "The text is not valid YAML.";
    }

    private static JsonNode convert(Object value, int depth, int[] budget) {
        if (--budget[0] < 0) {
            throw new IllegalArgumentException(
                    "The document expands to more than "
                            + MAX_TREE_NODES
                            + " values (anchors and aliases are counted every time they are used) and was refused.");
        }
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "The document is nested deeper than " + MAX_DEPTH + " levels.");
        }
        JsonNodeFactory f = JsonNodeFactory.instance;
        if (value == null) {
            return f.nullNode();
        }
        if (value instanceof String s) {
            return f.textNode(s);
        }
        if (value instanceof Boolean b) {
            return f.booleanNode(b);
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return f.numberNode(((Number) value).intValue());
        }
        if (value instanceof Long l) {
            return f.numberNode(l);
        }
        if (value instanceof BigInteger bi) {
            return f.numberNode(bi);
        }
        if (value instanceof BigDecimal bd) {
            return f.numberNode(bd);
        }
        if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d)
                    ? f.textNode(String.valueOf(d))
                    : f.numberNode(d);
        }
        if (value instanceof Date date) {
            return f.textNode(Instant.ofEpochMilli(date.getTime()).toString());
        }
        if (value instanceof byte[] bytes) {
            return f.textNode(Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof Map<?, ?> map) {
            ObjectNode out = f.objectNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "null" : String.valueOf(entry.getKey());
                out.set(key, convert(entry.getValue(), depth + 1, budget));
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            ArrayNode out = f.arrayNode();
            for (Object item : collection) {
                out.add(convert(item, depth + 1, budget));
            }
            return out;
        }
        return f.textNode(String.valueOf(value));
    }
}
