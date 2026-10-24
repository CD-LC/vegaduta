package com.agenticai.core.agents.turn.approval;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.ApprovalContinuation;
import com.agenticai.core.agents.turn.ApprovalContinuation.Outcome;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds an {@link ApprovalContinuation} from a decided approval request: the replay envelope
 * ({@code HumanApprovalGateService#recordToolCallWithOrigin}) says which tool was asked for, with
 * what arguments, for which goal, in which conversation and as whom; the caller says how the call
 * ended; the tool-call log says which tools the conversation already used; the approval table says
 * which other actions of the conversation still wait.
 *
 * <p>Returns empty - and says why in the log - for a request that cannot be continued: not a tool
 * call, recorded before the envelope carried the goal, or whose scoped history id does not match
 * what the recorded tenant, agent, subject and session derive (a mismatch means the envelope was
 * not written by the turn it claims to come from, and a continuation under it would read, and write,
 * another conversation's history). Needs the tenant context of the request's tenant on the calling
 * thread, for the tool-call log and the approval table.
 */
@Component
public class ApprovalContinuationFactory {

    private static final Logger log = LoggerFactory.getLogger(ApprovalContinuationFactory.class);

    private static final int OTHER_PENDING_ARGUMENT_CHARS = 200;

    private final ObjectMapper objectMapper;
    private final HumanApprovalGateService approvals;
    private final ObjectProvider<ToolCallLogService> toolCallLog;
    private final ApprovalContinuationProperties properties;

    public ApprovalContinuationFactory(
            ObjectMapper objectMapper,
            HumanApprovalGateService approvals,
            ObjectProvider<ToolCallLogService> toolCallLog,
            ApprovalContinuationProperties properties) {
        this.objectMapper = objectMapper;
        this.approvals = approvals;
        this.toolCallLog = toolCallLog;
        this.properties = properties;
    }

    /**
     * The agent the request was made for (the one the caller addressed, before experiment routing), read from the replay
     * envelope; empty for a request that has none. Cheap and read-only: the launcher asks it before deciding whether the
     * task is continued at all, so the agent's own {@code approvals.continuation.enabled} can apply.
     */
    public Optional<UUID> requestedAgentId(ApprovalRequest request) {
        JsonNode replay = replayEnvelope(request);
        return replay == null ? Optional.empty() : Optional.ofNullable(uuid(text(replay, "requestedAgentId")));
    }

    /**
     * @param text the tool's output, the failure message or the reviewer's reason, per {@code
     *     outcome}
     */
    public Optional<ApprovalContinuation> build(ApprovalRequest request, Outcome outcome, String text) {
        JsonNode replay = replayEnvelope(request);
        if (replay == null) {
            return Optional.empty();
        }
        String tool = text(replay, "toolName");
        String goal = text(replay, "goal");
        String sessionId = text(replay, "sessionId");
        String subjectKey = text(replay, "subjectKey");
        String tenantId = text(replay, "tenantId");
        String subject = text(replay, "subject");
        String memorySessionId = text(replay, "memorySessionId");
        UUID requestedAgentId = uuid(text(replay, "requestedAgentId"));
        UUID routedAgentId = uuid(text(replay, "agentId"));
        if (goal == null
                || sessionId == null
                || subjectKey == null
                || tenantId == null
                || subject == null
                || memorySessionId == null
                || requestedAgentId == null) {
            log.info(
                    "Approval request {} ('{}') carries no goal/conversation identity in its replay envelope (recorded"
                            + " before continuations existed, or by a caller that has none): the tool is handled but the"
                            + " task is not continued",
                    request.getId(),
                    tool);
            return Optional.empty();
        }
        String derived = SessionMemoryService.scopedSessionId(tenantId, requestedAgentId, subjectKey, sessionId);
        if (!derived.equals(memorySessionId)) {
            log.warn(
                    "Approval request {}: the recorded history id does not match the one its tenant, agent, subject and"
                            + " session derive - NOT continuing under it",
                    request.getId());
            return Optional.empty();
        }
        return Optional.of(
                new ApprovalContinuation(
                        request.getId(),
                        outcome,
                        tool,
                        argumentsOf(request),
                        text,
                        request.getDecidedBy(),
                        goal,
                        requestedAgentId,
                        sessionId,
                        subjectKey,
                        tenantId,
                        subject,
                        memorySessionId,
                        stickyTools(routedAgentId, sessionId, tool),
                        otherPending(request, routedAgentId, sessionId),
                        runToolNames(replay)));
    }

    /** The tools the conversation used lately, most recently used first, the approved tool included. */
    private List<String> stickyTools(UUID routedAgentId, String sessionId, String approvedTool) {
        int max = properties.getMaxStickyTools();
        if (max <= 0) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        if (approvedTool != null) {
            names.add(approvedTool);
        }
        ToolCallLogService logs = toolCallLog.getIfAvailable();
        if (logs != null && routedAgentId != null) {
            try {
                List<ToolCallLog> rows =
                        new ArrayList<>(
                                logs.findSince(
                                        routedAgentId,
                                        sessionId,
                                        Instant.now().minus(properties.getStickyToolsWindow())));
                for (int i = rows.size() - 1; i >= 0 && names.size() < max; i--) {
                    String name = rows.get(i).getToolName();
                    if (name != null && !name.isBlank()) {
                        names.add(name);
                    }
                }
            } catch (RuntimeException e) {
                log.warn(
                        "Could not read which tools session '{}' already used; the continuation offers the approved"
                                + " tool and whatever the goal matches",
                        sessionId,
                        e);
            }
        }
        return List.copyOf(names);
    }

    /** One line per other action of this conversation still waiting for a human. */
    private List<String> otherPending(ApprovalRequest decided, UUID routedAgentId, String sessionId) {
        int max = properties.getMaxOtherPending();
        if (max <= 0 || routedAgentId == null) {
            return List.of();
        }
        try {
            List<String> lines = new ArrayList<>();
            for (ApprovalRequest other :
                    approvals.findPendingRequestedBy("agent:" + routedAgentId + ":session:" + sessionId)) {
                if (lines.size() >= max) {
                    break;
                }
                if (other.getId() != null && other.getId().equals(decided.getId())) {
                    continue;
                }
                lines.add(describe(other));
            }
            return lines;
        } catch (RuntimeException e) {
            log.warn("Could not list the other pending approvals of session '{}'", sessionId, e);
            return List.of();
        }
    }

    private String describe(ApprovalRequest other) {
        String tool = other.getActionType();
        String arguments = "";
        JsonNode replay = replayEnvelope(other);
        if (replay != null) {
            tool = text(replay, "toolName") == null ? tool : text(replay, "toolName");
            arguments = argumentsOf(other);
            if ("{}".equals(arguments)) {
                arguments = "";
            }
        }
        if (arguments.length() > OTHER_PENDING_ARGUMENT_CHARS) {
            arguments = arguments.substring(0, OTHER_PENDING_ARGUMENT_CHARS) + "...";
        }
        return tool + (arguments.isEmpty() ? "" : " " + arguments) + " (approval request " + other.getId() + ")";
    }

    /**
     * What the call was asked to do. A guarded tool call keeps the model's arguments under {@code arguments};
     * an action a tool gated itself on (a code-workspace run or apply, a channel send) keeps its own fields at
     * the top level next to the {@code replay} envelope, so they are the arguments.
     */
    private String argumentsOf(ApprovalRequest request) {
        JsonNode payload = payload(request);
        JsonNode arguments = payload.path("arguments");
        if (!arguments.isMissingNode()) {
            return arguments.toString();
        }
        if (payload instanceof com.fasterxml.jackson.databind.node.ObjectNode object && object.size() > 1) {
            com.fasterxml.jackson.databind.node.ObjectNode own = object.deepCopy();
            own.remove("replay");
            return own.toString();
        }
        return "{}";
    }

    /** The {@code replay} object of a tool-call request's payload, or {@code null} when it has none. */
    private JsonNode replayEnvelope(ApprovalRequest request) {
        JsonNode payload = payload(request);
        JsonNode replay = payload.path("replay");
        return replay.hasNonNull("toolName") ? replay : null;
    }

    private JsonNode payload(ApprovalRequest request) {
        String json = request.getPayloadJson();
        if (json == null || json.isBlank()) {
            return objectMapper.missingNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.missingNode();
        }
    }

    /**
     * The run-scoped tool selection the parked turn carried (an array, possibly empty), or {@code null} when the
     * turn was not narrowed or the envelope predates the field.
     */
    private static List<String> runToolNames(JsonNode replay) {
        JsonNode names = replay.path("runToolNames");
        if (!names.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode name : names) {
            if (name.isTextual() && !name.asText().isBlank()) {
                out.add(name.asText());
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
