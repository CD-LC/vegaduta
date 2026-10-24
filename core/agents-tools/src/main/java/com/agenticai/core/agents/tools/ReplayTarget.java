package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.approval.ApprovalRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

/**
 * Everything the replay of an approved tool call needs, resolved from either payload shape
 * ({@link #resolve}). Nullable: {@code tenantId}, {@code subject}, {@code toolDigest}, {@code
 * memorySessionId} (the SEC-004 scoped id the originating turn reads its history under; absent on
 * rows recorded before it was captured).
 *
 * <p>Read by {@link AgentToolCallApprovalExecutor}; the platform's continuation of the task after
 * the decision reads the same envelope through {@code ApprovalContinuationFactory}, which needs
 * more of it (the goal, the memory subject) and is stricter about what it accepts.
 */
record ReplayTarget(
        UUID agentId,
        String sessionId,
        String toolName,
        String argumentsJson,
        String tenantId,
        String subject,
        String toolDigest,
        String memorySessionId) {

    /**
     * Kept as a constant so the executor can recognize (and skip) synthetic ids: a request that
     * names no session has no conversation to tell.
     */
    static final String FALLBACK_SESSION_PREFIX = "approval-replay:";

    /**
     * Two payload shapes exist in the wild and both must replay:
     *
     * <ol>
     *   <li><b>Envelope</b> (7-arg {@code recordToolCallForApproval}, the shape everything records
     *       now): {@code {"replay": {...}, "arguments": ...}}. Discriminated by {@code
     *       replay.toolName} being present, not by the {@code "replay"} key alone - the legacy
     *       payload is model-authored tool input and could contain a {@code "replay"} key of its
     *       own.
     *   <li><b>Legacy</b> (raw tool arguments, rows recorded before the envelope existed): tool
     *       name recovered from the action type, agent/session from {@code requestedBy}'s {@code
     *       "agent:<id>:session:<id>"} label, tenant inherited from the calling thread (the
     *       approve/retry paths both set it correctly - see the executor's class javadoc).
     * </ol>
     */
    static ReplayTarget resolve(ApprovalRequest request, ObjectMapper objectMapper) {
        String payloadJson = request.getPayloadJson();
        JsonNode payload = null;
        if (payloadJson != null && !payloadJson.isBlank()) {
            try {
                payload = objectMapper.readTree(payloadJson);
            } catch (Exception e) {
                throw new AgentToolReplayException(
                        "Approval request "
                                + request.getId()
                                + " has an unparseable payload - cannot replay: "
                                + e.getMessage(),
                        e);
            }
        }
        if (payload != null && payload.path("replay").hasNonNull("toolName")) {
            return fromEnvelope(request, payload);
        }
        return fromLegacyPayload(request, payloadJson);
    }

    private static ReplayTarget fromEnvelope(ApprovalRequest request, JsonNode payload) {
        JsonNode replay = payload.path("replay");
        UUID agentId = parseUuid(replay.path("agentId").asText(null));
        if (agentId == null) {
            throw new AgentToolReplayException(
                    "Approval request "
                            + request.getId()
                            + " has a replay envelope with no valid agentId - cannot replay.");
        }
        String arguments;
        if (payload.has("arguments")) {
            arguments = payload.get("arguments").toString();
        } else {
            arguments = payload.path("argumentsRaw").asText("{}");
        }
        String sessionId = replay.path("sessionId").asText(null);
        return new ReplayTarget(
                agentId,
                sessionId != null && !sessionId.isBlank() ? sessionId : fallbackSessionId(request),
                replay.path("toolName").asText(),
                arguments,
                replay.path("tenantId").asText(null),
                replay.path("subject").asText(null),
                replay.path("toolDigest").asText(null),
                replay.path("memorySessionId").asText(null));
    }

    private static ReplayTarget fromLegacyPayload(ApprovalRequest request, String payloadJson) {
        String actionType = request.getActionType();
        String toolName = actionType.substring(AgentToolCallApprovalExecutor.ACTION_TYPE_PREFIX.length());
        UUID agentId = null;
        String sessionId = null;
        String requestedBy = request.getRequestedBy();
        // GuardedToolCallback's label: "agent:<uuid>:session:<sessionId>".
        if (requestedBy != null && requestedBy.startsWith("agent:")) {
            int sessionMarker = requestedBy.indexOf(":session:");
            if (sessionMarker > "agent:".length()) {
                agentId = parseUuid(requestedBy.substring("agent:".length(), sessionMarker));
                sessionId = requestedBy.substring(sessionMarker + ":session:".length());
            }
        }
        if (agentId == null) {
            throw new AgentToolReplayException(
                    "Approval request "
                            + request.getId()
                            + " predates the replay envelope and its requestedBy label ('"
                            + requestedBy
                            + "') does not identify the requesting agent - cannot replay. "
                            + "Ask the user to retry the action in chat.");
        }
        return new ReplayTarget(
                agentId,
                sessionId != null && !sessionId.isBlank() ? sessionId : fallbackSessionId(request),
                toolName,
                payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson,
                null,
                null,
                null,
                null);
    }

    private static String fallbackSessionId(ApprovalRequest request) {
        return FALLBACK_SESSION_PREFIX + request.getId();
    }

    /**
     * The scoped history id for this target: the one captured in the envelope, else derived from
     * the envelope's tenant and subject, else {@code null}. See the executor's memory-note method.
     */
    String memorySessionIdOrDerived() {
        if (memorySessionId != null && !memorySessionId.isBlank()) {
            return memorySessionId;
        }
        if (tenantId != null && subject != null) {
            return SessionMemoryService.scopedSessionId(tenantId, agentId, subject, sessionId);
        }
        return null;
    }

    /**
     * The subject stamped on the replay's tenant context - the originating human when recorded, a
     * system label otherwise, so audit writes made during the replay never attribute to the
     * deciding admin something an agent's session actually asked for.
     */
    String replaySubject() {
        return subject != null && !subject.isBlank() ? subject : "system:approval-replay";
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
