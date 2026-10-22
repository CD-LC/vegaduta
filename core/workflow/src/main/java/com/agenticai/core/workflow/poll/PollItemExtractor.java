package com.agenticai.core.workflow.poll;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a poll response body into the item list a trigger fires on. Paths are deliberately tiny:
 * dot-separated object fields, with a numeric segment meaning an array index ({@code
 * "data.items.0.id"}). This is NOT JSONPath - no filters, no wildcards - because "point at the
 * array, point at the id" covers the APIs this feature targets (list endpoints returning {@code
 * {"items":[{...}]}} or a bare {@code [...]}) and a bigger query language is its own security
 * surface.
 *
 * <p>An item with no id at {@code idPath} (or a blank/null one) falls back to the SHA-256 of its
 * serialized content - the same "dedup on identical payload" convention {@code
 * WorkflowWebhookController} uses for senders with no delivery-id header. Stable content means a
 * stable id means it fires once.
 */
public final class PollItemExtractor {

    private PollItemExtractor() {}

    /** One pollable item: its dedup id and the raw JSON the run receives. */
    public record PolledItem(String id, JsonNode item) {}

    /**
     * Parses {@code body} and extracts items. {@code itemsPath} empty means "the response itself is
     * the payload": a root array yields each element, anything else yields that single value.
     * {@code idPath} empty (or unresolvable on an item) falls back to the content hash.
     */
    public static List<PolledItem> extract(
            ObjectMapper objectMapper, String body, String itemsPath, String idPath) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null || body.isBlank() ? "null" : body);
        } catch (Exception e) {
            throw new PollFetchException("the endpoint returned a body that is not valid JSON");
        }
        JsonNode listNode = navigate(root, itemsPath);
        List<JsonNode> elements = new ArrayList<>();
        if (listNode == null || listNode.isNull()) {
            return List.of();
        } else if (listNode.isArray()) {
            listNode.forEach(elements::add);
        } else {
            elements.add(listNode);
        }
        List<PolledItem> items = new ArrayList<>(elements.size());
        for (JsonNode element : elements) {
            items.add(new PolledItem(itemId(element, idPath), element));
        }
        return items;
    }

    /** Dot-path navigation; null when any segment misses. */
    private static JsonNode navigate(JsonNode node, String path) {
        if (path == null || path.isBlank()) {
            return node;
        }
        JsonNode cur = node;
        for (String segment : path.split("\\.")) {
            if (cur == null || segment.isEmpty()) {
                return null;
            }
            if (cur.isArray() && segment.chars().allMatch(Character::isDigit)) {
                cur = cur.get(Integer.parseInt(segment));
            } else {
                cur = cur.get(segment);
            }
        }
        return cur;
    }

    private static String itemId(JsonNode item, String idPath) {
        JsonNode id = navigate(item, idPath);
        if (id != null && id.isValueNode() && !id.asString().isBlank()) {
            return id.asString();
        }
        return "sha256:" + sha256Hex(item.toString());
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // SHA-256 is guaranteed on every JDK.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
