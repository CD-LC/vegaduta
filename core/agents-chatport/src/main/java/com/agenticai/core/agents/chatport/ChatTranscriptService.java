package com.agenticai.core.agents.chatport;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.memory.CallerSessionIds;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TranscriptView;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/agents/{agentId}/sessions/{sessionId}/transcript}: the stored conversation of a
 * live session, as the web restores it after a reload - every message with an explicit {@code
 * status} (ok / failed / interrupted), and a failed reply as the message the person was shown
 * rather than the stored technical marker.
 *
 * <p>Same access rule as the export: RUNNER on the agent AND the caller is the tenant + subject
 * that started the session (a client-chosen session id alone never reads a conversation). Not
 * behind the export flag: restoring what you just saw is not an export.
 */
@Service
public class ChatTranscriptService {

    static final int MAX_SESSION_ID_CHARS = 200;
    static final String NO_SESSION_MESSAGE = "That conversation is no longer available.";

    private final AgentService agentService;
    private final SessionMemoryService sessionMemoryService;

    public ChatTranscriptService(
            AgentService agentService, SessionMemoryService sessionMemoryService) {
        this.agentService = agentService;
        this.sessionMemoryService = sessionMemoryService;
    }

    /** {@code messages} oldest first. */
    public record Transcript(String sessionId, List<TranscriptView.Entry> messages) {}

    public Transcript transcript(UUID agentId, String sessionId) {
        agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        if (sessionId == null
                || sessionId.isBlank()
                || sessionId.length() > MAX_SESSION_ID_CHARS
                || sessionId.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7F)) {
            throw new IllegalArgumentException("That session id is not valid.");
        }
        // SEC-004: history and its owner record live under the caller-scoped id, never the raw one.
        String memorySessionId =
                CallerSessionIds.scopedForCurrentCaller(agentId, sessionId)
                        .filter(this::callerOwns)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, NO_SESSION_MESSAGE));
        List<ChatTurn> turns = sessionMemoryService.getFullHistory(memorySessionId);
        if (turns == null || turns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NO_SESSION_MESSAGE);
        }
        List<TranscriptView.Entry> messages = new ArrayList<>(turns.size());
        for (ChatTurn turn : turns) {
            if (turn != null) {
                messages.add(TranscriptView.of(turn));
            }
        }
        return new Transcript(sessionId, messages);
    }

    private boolean callerOwns(String memorySessionId) {
        return TenantContext.current()
                .map(
                        ctx ->
                                sessionMemoryService.isOwnedBy(
                                        memorySessionId, ctx.getTenantId(), ctx.getSubject()))
                .orElse(false);
    }
}
