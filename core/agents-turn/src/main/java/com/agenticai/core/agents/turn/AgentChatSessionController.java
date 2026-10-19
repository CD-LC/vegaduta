package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentController;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentReliabilitySummary;
import com.agenticai.core.agents.dto.ChatAttachmentResponse;
import com.agenticai.core.agents.dto.ChatRequest;
import com.agenticai.core.agents.dto.SessionRewindRequest;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The per-agent chat-session side of {@link AgentController}: turn cancel, session rewind,
 * attachment upload, tool-call history and the reliability panel. Split out of {@code
 * AgentController} (file-size ratchet) - every path below is byte-identical to what that controller
 * served before the split; only the owning class changed. The base path is {@code
 * /api/agents/{agentId}} rather than {@code /api/agents} so the two controllers do not share one
 * class-level mapping (see {@code scripts/ci/entity-name-lint.sh}'s base-path namespace).
 */
@RestController
@RequestMapping("/api/agents/{agentId}")
public class AgentChatSessionController {

    private final AgentService agentService;
    private final ToolCallLogService toolCallLogService;
    private final ChatAttachmentService chatAttachmentService;
    private final AgentReliabilityService agentReliabilityService;
    private final ChatTurnCancelService chatTurnCancelService;
    private final SessionMemoryService sessionMemoryService;

    public AgentChatSessionController(
            AgentService agentService,
            ToolCallLogService toolCallLogService,
            ChatAttachmentService chatAttachmentService,
            AgentReliabilityService agentReliabilityService,
            ChatTurnCancelService chatTurnCancelService,
            SessionMemoryService sessionMemoryService) {
        this.agentService = agentService;
        this.toolCallLogService = toolCallLogService;
        this.chatAttachmentService = chatAttachmentService;
        this.agentReliabilityService = agentReliabilityService;
        this.chatTurnCancelService = chatTurnCancelService;
        this.sessionMemoryService = sessionMemoryService;
    }

    /**
     * Server-side turn cancel (V261 sibling, "run control") - stops the in-flight turn of this
     * agent+session from ANOTHER request, possibly on another replica: the running stream polls a
     * Redis flag every 500ms and cancels itself when it appears, which fires the same
     * partial-persist path a client disconnect does ({@code
     * AgentChatService#INTERRUPTED_REPLY_MARKER}).
     *
     * <p>RUNNER, same bar as {@link AgentController#chat} itself: stopping a turn is the same
     * privilege as starting one. Session-scoped on purpose - the turn is the unit an operator can
     * actually identify (the session id came back in {@code X-Session-Id}); there is no separate
     * per-turn handle to enumerate. 202, not 200: this records a signal - the turn ends on its own
     * next poll (~500ms), and whether a turn was even in flight is not knowable here (a stale flag
     * simply expires - see {@link ChatTurnCancelService}'s javadoc).
     *
     * <p>The flag is keyed by the caller-scoped session id (the one the turn watches, see {@link
     * #scopedMemorySessionId}), so knowing another user's raw session id does not let a runner stop
     * their turn.
     */
    @PostMapping("/chat/sessions/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelChatTurn(
            @PathVariable UUID agentId, @PathVariable String sessionId, Principal principal) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        chatTurnCancelService.requestCancellation(
                agentId, scopedMemorySessionId(agentId, sessionId, principal));
        return ResponseEntity.accepted()
                .body(Map.of("sessionId", sessionId, "cancelRequested", true));
    }

    /**
     * Time-travel rewind: truncate the session transcript so the conversation continues as if only
     * the first {@code keepTurns} entries ever happened. The client then resends or edits the
     * message it wants to branch from - this endpoint deliberately does not re-run a turn, so no
     * tool call or side effect the original turn already fired is repeated. The rolling summary is
     * dropped with the tail (folding it would resurrect exactly the content the user rewound away -
     * see {@link SessionMemoryService#rewind}).
     *
     * <p>RUNNER, same bar as {@link #cancelChatTurn}: rewinding a session is the same privilege as
     * speaking inside it.
     */
    @PostMapping("/chat/sessions/{sessionId}/rewind")
    public ResponseEntity<Map<String, Object>> rewindChatSession(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            @Valid @RequestBody SessionRewindRequest request,
            Principal principal) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        String memorySessionId = scopedMemorySessionId(agentId, sessionId, principal);
        int before =
                sessionMemoryService
                        .getFullHistory(memorySessionId)
                        .size(); // SEC-004: only the CALLER's own conversation
        int kept = sessionMemoryService.rewind(memorySessionId, request.keepTurns());
        return ResponseEntity.ok(
                Map.of(
                        "sessionId", sessionId,
                        "keepTurns", kept,
                        "droppedTurns", before - kept));
    }

    /**
     * Stage 4 (Chat file upload) - validates and stores one image/audio/video file for use in a
     * subsequent {@link AgentController#chat} turn on this same agent (see {@link ChatRequest}'s
     * javadoc for how the two round-trip together). Deliberately a separate call rather than
     * accepting the file directly on {@link AgentController#chat}: {@code chat} is {@code
     * application/json} today and multipart+SSE on one endpoint is awkward with Spring MVC, and
     * splitting upload from send lets the frontend show an upload-progress/preview chip before the
     * person hits send.
     */
    @PostMapping(value = "/chat/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatAttachmentResponse uploadChatAttachment(
            @PathVariable UUID agentId, @RequestParam("file") MultipartFile file) {
        return chatAttachmentService.upload(agentId, file);
    }

    /**
     * Stage 4: retrieve every tool call this agent has made, most recent first. VIEWER-gated (Round
     * 2 resource-sharing retrofit) - tool-call history is internal execution detail beyond plain
     * RUNNER (execute-only) visibility.
     */
    @GetMapping("/tool-calls")
    public List<ToolCallLogView> toolCalls(@PathVariable UUID agentId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return toolCallLogService.findByAgentId(agentId).stream()
                .map(ToolCallLogView::from)
                .toList();
    }

    /**
     * Task #959 ("Agent performance: response time + reliability + failure handling") - the
     * per-agent reliability panel: average response time, success rate, tool-call reliability,
     * fallback usage, and a short recent- failure list, all for this one agent. See {@link
     * AgentReliabilityService}'s javadoc for exactly which existing tables this reads from (no new
     * instrumentation - every source was already being recorded for other features).
     */
    @GetMapping("/reliability")
    public AgentReliabilitySummary reliability(@PathVariable UUID agentId) {
        return agentReliabilityService.summarize(agentId);
    }

    public record ToolCallLogView(
            UUID id,
            String sessionId,
            String toolName,
            String toolInput,
            String toolOutput,
            String errorMessage,
            java.time.Instant startedAt,
            java.time.Instant completedAt,
            // Data-governance audit fix (2026-07-20) - null unless this tool call happened during
            // an
            // inbound MCP-grant-triggered chat turn; see ToolCallLog#getMcpGrantId's own javadoc.
            UUID mcpGrantId) {
        static ToolCallLogView from(com.agenticai.core.agents.tools.ToolCallLog log) {
            return new ToolCallLogView(
                    log.getId(),
                    log.getSessionId(),
                    log.getToolName(),
                    log.getToolInput(),
                    log.getToolOutput(),
                    log.getErrorMessage(),
                    log.getStartedAt(),
                    log.getCompletedAt(),
                    log.getMcpGrantId());
        }
    }

    /**
     * SEC-004: the scoped session-memory id for {@code clientSessionId} as seen by THIS caller -
     * tenant from {@link com.agenticai.core.security.TenantContext}, subject derived exactly like
     * {@link AgentController#chat} derives its {@code subjectKey}, so it always resolves to the
     * list {@code AgentChatService#chat} wrote for this caller and never to anyone else's.
     * Package-private so {@code AgentController} and in-package tests can keep calling it.
     */
    static String scopedMemorySessionId(UUID agentId, String clientSessionId, Principal principal) {
        String tenantId =
                com.agenticai.core.security.TenantContext.current()
                        .map(com.agenticai.core.security.TenantContext::getTenantId)
                        .orElse(null);
        String subjectKey =
                principal != null && StringUtils.hasText(principal.getName())
                        ? principal.getName()
                        : clientSessionId;
        return SessionMemoryService.scopedSessionId(tenantId, agentId, subjectKey, clientSessionId);
    }
}
