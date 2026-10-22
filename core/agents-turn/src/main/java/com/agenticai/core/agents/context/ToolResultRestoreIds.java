package com.agenticai.core.agents.context;

import com.agenticai.core.json.JsonMappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * The pointer a cleared tool result carries and {@code restore_tool_result} resolves: {@code
 * <toolName>#<argsDigest>} (the digest is a short SHA-256 of the canonical JSON of the call's
 * arguments, so two calls of the same tool with the same arguments share a pointer and the latest
 * one wins), or the raw {@code tool_call_log} row id when the model already holds one.
 *
 * <p>The digest is computed on both sides - when the placeholder is written (from the assistant
 * message's tool call) and when the stored row is searched (from {@code tool_call_log.tool_input})
 * - over the CANONICAL form of the JSON (sorted keys, no whitespace), so a harmless
 * re-serialisation between the model's call and the logged input cannot make a pointer miss its
 * row. A digest is not a credential and not an access path: the lookup that follows is still bound
 * to the calling tenant, agent and session.
 */
public final class ToolResultRestoreIds {

    private static final ObjectMapper CANONICAL =
            JsonMappers.plain().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final int DIGEST_BYTES = 6;

    private ToolResultRestoreIds() {}

    /** What a model-supplied {@code stepId} resolves to. */
    public record Parsed(UUID logId, String toolName, String argsDigest) {
        public boolean byLogId() {
            return logId != null;
        }
    }

    /** {@code <toolName>#<argsDigest>} for a tool call. */
    public static String stepId(String toolName, String toolInput) {
        return (toolName == null ? "" : toolName) + "#" + digest(toolInput);
    }

    /** 12 hex characters identifying a call's arguments; stable across whitespace and key order. */
    public static String digest(String toolInput) {
        String canonical = canonical(toolInput);
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(DIGEST_BYTES * 2);
            for (int i = 0; i < DIGEST_BYTES; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM; falling back keeps the pointer deterministic
            // anyway.
            return Integer.toHexString(canonical.hashCode());
        }
    }

    /**
     * Sorted-key, whitespace-free JSON when {@code toolInput} parses as JSON, else the trimmed
     * text.
     */
    static String canonical(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return "{}";
        }
        String trimmed = toolInput.trim();
        try {
            JsonNode node = CANONICAL.readTree(trimmed);
            if (node == null || node.isMissingNode()) {
                return trimmed;
            }
            Object tree = CANONICAL.treeToValue(node, Object.class);
            return CANONICAL.writeValueAsString(tree);
        } catch (Exception e) {
            return trimmed;
        }
    }

    /**
     * Parses a model-supplied step id; empty when it is neither a log id nor {@code tool#digest}.
     */
    public static Optional<Parsed> parse(String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return Optional.empty();
        }
        String id = stepId.trim();
        try {
            return Optional.of(new Parsed(UUID.fromString(id), null, null));
        } catch (IllegalArgumentException notAUuid) {
            // fall through to the tool#digest form
        }
        int hash = id.lastIndexOf('#');
        if (hash <= 0 || hash == id.length() - 1) {
            return Optional.empty();
        }
        String tool = id.substring(0, hash).trim();
        String digest = id.substring(hash + 1).trim().toLowerCase(Locale.ROOT);
        if (tool.isEmpty() || !digest.matches("[0-9a-f]{4,64}")) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(null, tool, digest));
    }
}
