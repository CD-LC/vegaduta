package com.agenticai.core.agents.tools.approvaltrace;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.approval.ApprovalHistoryService;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.approval.ApprovalRequestNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Answers "what did the agent do after this approval?" from the records every turn already writes:
 * the approval request (decision, replay envelope, execution result) and the agent's {@code
 * tool_call_log} for the conversation the request came from. Nothing new is stored, so it also
 * covers approvals decided before this existed.
 */
@Service
public class ApprovalTraceService {

    /** How long after the decision the continuation's calls are looked for. */
    static final Duration WINDOW = Duration.ofMinutes(30);

    static final int MAX_CALLS = 200;
    static final int SUMMARY_CHARS = 240;

    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern LONG_TOKEN = Pattern.compile("[A-Za-z0-9+/_-]{32,}={0,2}");

    private final ApprovalHistoryService requests;
    private final ToolCallLogRepository calls;
    private final ObjectMapper objectMapper;

    public ApprovalTraceService(
            ApprovalHistoryService requests,
            ToolCallLogRepository calls,
            ObjectMapper objectMapper) {
        this.requests = requests;
        this.calls = calls;
        this.objectMapper = objectMapper;
    }

    /** The conversation a request came from, for the access check; empty when it names none. */
    public Optional<ApprovalTraceView.Conversation> conversationOf(UUID requestId) {
        return conversationOf(load(requestId));
    }

    public ApprovalTraceView trace(UUID requestId) {
        ApprovalRequest request = load(requestId);
        Optional<ApprovalTraceView.Conversation> conversation = conversationOf(request);
        Instant decidedAt = request.getDecidedAt();
        Instant windowEnd = decidedAt == null ? null : decidedAt.plus(WINDOW);
        List<ApprovalTraceView.Call> trace = List.of();
        boolean truncated = false;
        if (conversation.isPresent() && decidedAt != null) {
            List<ToolCallLog> logged =
                    calls
                            .findTop200ByAgentIdAndSessionIdAndStartedAtGreaterThanEqualOrderByStartedAtAsc(
                                    conversation.get().agentId(),
                                    conversation.get().sessionId(),
                                    decidedAt);
            truncated = logged.size() >= MAX_CALLS;
            trace =
                    logged.stream()
                            .filter(c -> !c.getStartedAt().isAfter(windowEnd))
                            .map(ApprovalTraceService::call)
                            .toList();
        }
        return new ApprovalTraceView(
                request.getId(),
                request.getActionType(),
                request.getStatus().name(),
                request.getCreatedAt(),
                request.getRequestedBy(),
                request.getDecidedBy(),
                decidedAt,
                request.getRejectionReason(),
                summary(request.getExecutionResult()),
                summary(request.getExecutionError()),
                request.getRetryCount(),
                conversation.orElse(null),
                windowEnd,
                truncated,
                trace,
                note(conversation.isPresent(), decidedAt != null, trace.isEmpty()));
    }

    private ApprovalRequest load(UUID id) {
        return requests.find(id).orElseThrow(() -> new ApprovalRequestNotFoundException(id));
    }

    private Optional<ApprovalTraceView.Conversation> conversationOf(ApprovalRequest request) {
        String payload = request.getPayloadJson();
        if (payload == null || payload.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode replay = objectMapper.readTree(payload).path("replay");
            String agent = replay.path("agentId").asText("");
            String session = replay.path("sessionId").asText("");
            if (agent.isBlank() || session.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(
                    new ApprovalTraceView.Conversation(
                            UUID.fromString(agent),
                            session,
                            summary(replay.path("goal").asText(null))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static ApprovalTraceView.Call call(ToolCallLog c) {
        boolean failed = c.getErrorMessage() != null && !c.getErrorMessage().isBlank();
        return new ApprovalTraceView.Call(
                c.getId(),
                c.getToolName(),
                c.getStartedAt(),
                c.getCompletedAt(),
                failed ? "FAILED" : "OK",
                summary(c.getToolInput()),
                summary(c.getToolOutput()),
                summary(c.getErrorMessage()));
    }

    /** One line, secrets and long opaque tokens masked, cut to {@link #SUMMARY_CHARS}. */
    static String summary(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.replaceAll("\\s+", " ").strip();
        text = BEARER.matcher(text).replaceAll("Bearer [redacted]");
        text = LONG_TOKEN.matcher(text).replaceAll("[redacted]");
        return text.length() <= SUMMARY_CHARS ? text : text.substring(0, SUMMARY_CHARS) + "...";
    }

    private static String note(boolean hasConversation, boolean decided, boolean noCalls) {
        if (!decided) {
            return "Not decided yet: nothing has run.";
        }
        if (!hasConversation) {
            return "This request did not come from an agent turn, so no agent was waiting for it and none"
                    + " continued. No trace of an agent is recorded for it.";
        }
        String scope =
                "Tool calls of the same conversation within "
                        + WINDOW.toMinutes()
                        + " minutes of the decision, matched by conversation and time (not individually"
                        + " tied to this approval). Inputs and outputs are shortened and masked.";
        return noCalls ? scope + " No tool call was recorded in that window." : scope;
    }
}
