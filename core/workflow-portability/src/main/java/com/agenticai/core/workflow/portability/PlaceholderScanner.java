package com.agenticai.core.workflow.portability;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the holes already written into a node's config - {@code @placeholder:<key>} tokens, {@code
 * [[CREDENTIAL_..]]} markers left by the redactor and {@code {{secret.NAME}}} vault references -
 * and records each in a {@link PlaceholderRegistry} so the preview can list them. Read-only over
 * the tree; bounded in depth and node count because the tree comes from an uploaded file.
 */
final class PlaceholderScanner {

    private static final Pattern CREDENTIAL =
            Pattern.compile("\\[\\[(CREDENTIAL_[A-Za-z0-9_]{1,80})]]");
    private static final Pattern SECRET_REF =
            Pattern.compile("\\{\\{\\s*secret\\s*[.:]\\s*([A-Za-z][A-Za-z0-9_]{0,63})\\s*}}");
    private static final int MAX_DEPTH = 40;
    private static final int MAX_VISITS = 100_000;

    private PlaceholderScanner() {}

    /**
     * Scans for tokens, credential markers and vault references.
     *
     * @param tree a node's config (maps, lists, strings - what Jackson's {@code convertValue}
     *     produces)
     * @param labelFor display labels for token keys, from the file's own placeholder list; may be
     *     empty
     */
    static void scan(
            Object tree,
            String nodeId,
            String nodeLabel,
            PlaceholderRegistry registry,
            Map<String, String> labelFor) {
        scan(tree, nodeId, nodeLabel, registry, labelFor, true);
    }

    /**
     * As {@link #scan(Object, String, String, PlaceholderRegistry, Map)}; {@code tokens=false}
     * skips {@code @placeholder:} tokens.
     */
    static void scan(
            Object tree,
            String nodeId,
            String nodeLabel,
            PlaceholderRegistry registry,
            Map<String, String> labelFor,
            boolean tokens) {
        int[] budget = {MAX_VISITS};
        walk(tree, "", nodeId, nodeLabel, registry, labelFor, tokens, 0, budget);
    }

    private static void walk(
            Object value,
            String field,
            String nodeId,
            String nodeLabel,
            PlaceholderRegistry registry,
            Map<String, String> labelFor,
            boolean tokens,
            int depth,
            int[] budget) {
        if (value == null || depth > MAX_DEPTH || --budget[0] < 0) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                walk(
                        entry.getValue(),
                        String.valueOf(entry.getKey()),
                        nodeId,
                        nodeLabel,
                        registry,
                        labelFor,
                        tokens,
                        depth + 1,
                        budget);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                walk(item, field, nodeId, nodeLabel, registry, labelFor, tokens, depth + 1, budget);
            }
        } else if (value instanceof String text) {
            String key = PlaceholderRegistry.keyOfToken(text);
            if (key != null) {
                if (tokens) {
                    String label = labelFor.getOrDefault(key, nodeLabel);
                    registry.registerExact(
                            key, PlaceholderRegistry.kindOfKey(key), label, nodeId, field);
                }
                return;
            }
            Matcher cred = CREDENTIAL.matcher(text);
            while (cred.find()) {
                registry.registerExact(
                        cred.group(1),
                        PlaceholderRegistry.CREDENTIAL,
                        "Credential in " + nodeLabel,
                        nodeId,
                        field);
            }
            Matcher secret = SECRET_REF.matcher(text);
            while (secret.find()) {
                registry.registerExact(
                        "secret:" + secret.group(1),
                        PlaceholderRegistry.SECRET,
                        secret.group(1),
                        nodeId,
                        field);
            }
        }
    }
}
