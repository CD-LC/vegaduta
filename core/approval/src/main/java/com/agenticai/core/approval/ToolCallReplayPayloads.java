package com.agenticai.core.approval;

import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/**
 * Builds the versioned replay envelope persisted with a gated tool call (see {@link
 * HumanApprovalGateService}).
 */
final class ToolCallReplayPayloads {

    private ToolCallReplayPayloads() {}

    /**
     * Shape: {@code {"replay": {"version": 1, "toolName", "agentId", "sessionId",
     * "memorySessionId", "goal", "requestedAgentId", "subjectKey", "runToolNames", "tenantId",
     * "subject"}, "arguments": {...}}}. The top-level {@code "replay"} key is the discriminator
     * {@code AgentToolCallApprovalExecutor} uses to tell this envelope apart from a legacy
     * raw-arguments payload - raw tool input is model-authored and could itself contain a {@code
     * "replay"} key, which is why the executor also requires {@code replay.toolName} before
     * trusting it. Arguments are embedded as a parsed node when they are valid JSON (readable on
     * the Approvals screen, semantically lossless on re-serialization); an unparseable input is
     * preserved verbatim under {@code "argumentsRaw"} instead so replay hands the tool exactly what
     * the model originally produced.
     */
    static String buildPayload(
            ObjectMapper objectMapper,
            String toolName,
            String argumentsJson,
            UUID agentId,
            String sessionId,
            String toolDigest,
            ToolCallOrigin origin) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.set(
                "replay",
                replayEnvelope(objectMapper, toolName, agentId, sessionId, toolDigest, origin));
        String arguments = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
        try {
            payload.set("arguments", objectMapper.readTree(arguments));
        } catch (Exception e) {
            payload.put("argumentsRaw", arguments);
        }
        return payload.toString();
    }

    /** The {@code replay} object of the envelope: the call's identity and where it came from. */
    static ObjectNode replayEnvelope(
            ObjectMapper objectMapper,
            String toolName,
            UUID agentId,
            String sessionId,
            String toolDigest,
            ToolCallOrigin origin) {
        ObjectNode replay = objectMapper.createObjectNode();
        replay.put("version", 1);
        replay.put("toolName", toolName);
        replay.put("agentId", agentId != null ? agentId.toString() : null);
        replay.put("sessionId", sessionId);
        if (toolDigest != null && !toolDigest.isBlank()) {
            replay.put("toolDigest", toolDigest);
        }
        if (origin != null) {
            putIfPresent(replay, "memorySessionId", origin.memorySessionId());
            putIfPresent(replay, "goal", origin.goal());
            putIfPresent(
                    replay,
                    "requestedAgentId",
                    origin.requestedAgentId() == null
                            ? null
                            : origin.requestedAgentId().toString());
            putIfPresent(replay, "subjectKey", origin.subjectKey());
            if (origin.runToolNames() != null) {
                // Present even when empty: "narrowed to no tools" is not "not narrowed".
                com.fasterxml.jackson.databind.node.ArrayNode names =
                        replay.putArray("runToolNames");
                origin.runToolNames().forEach(names::add);
            }
        }
        TenantContext.current()
                .ifPresent(
                        context -> {
                            replay.put("tenantId", context.getTenantId());
                            replay.put("subject", context.getSubject());
                        });
        return replay;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }
}
