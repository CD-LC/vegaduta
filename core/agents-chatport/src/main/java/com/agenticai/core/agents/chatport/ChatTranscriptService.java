package com.agenticai.core.agents.chatport;

import com.agenticai.core.agents.branching.ChatBranchDtos.MessageView;
import com.agenticai.core.agents.branching.ChatBranchService;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/agents/{agentId}/sessions/{sessionId}/transcript}: the stored conversation of a
 * live session, as the web restores it after a reload - every message with an explicit {@code
 * status} (ok / failed / interrupted), and a failed reply as the message the person was shown
 * rather than the stored technical marker. A turn that only carries a platform tool record (the
 * outcome of an action a person decided, {@link ChatTurn#isToolRecordOnly}) is the model's, not a
 * message, and is left out.
 *
 * <p>Same access rule as the export: RUNNER on the agent AND the caller is the tenant + subject
 * that started the session (a client-chosen session id alone never reads a conversation). Not
 * behind the export flag: restoring what you just saw is not an export.
 *
 * <p>Redis keeps a session for 24h. When it holds nothing for the caller (expired, or a new device
 * reopening a thread from {@code GET /api/chat/threads}) and chat branching is on, the stored
 * tree's active path is returned instead ({@link ChatBranchService#activePath}, which enforces its
 * own ownership: a tree bound to another subject answers 404 there too). With branching off, or no
 * tree, the session is simply not found, as before.
 */
@Service
public class ChatTranscriptService {

    static final int MAX_SESSION_ID_CHARS = 200;
    static final String NO_SESSION_MESSAGE = "That conversation is no longer available.";

    private final AgentService agentService;
    private final SessionMemoryService sessionMemoryService;
    private final ObjectProvider<ChatBranchService> branches;

    public ChatTranscriptService(
            AgentService agentService,
            SessionMemoryService sessionMemoryService,
            ObjectProvider<ChatBranchService> branches) {
        this.agentService = agentService;
        this.sessionMemoryService = sessionMemoryService;
        this.branches = branches;
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
        // A session nobody owns any more (expired owner record) reads as empty, not as a miss, so
        // the stored tree gets its turn below.
        List<ChatTurn> turns =
                CallerSessionIds.scopedForCurrentCaller(agentId, sessionId)
                        .filter(this::callerOwns)
                        .map(sessionMemoryService::getFullHistory)
                        .orElse(List.of());
        List<TranscriptView.Entry> messages = new ArrayList<>();
        if (turns != null) {
            for (ChatTurn turn : turns) {
                // A record-only turn (a decided action's outcome, kept for the model) is not a message.
                if (turn != null && !ChatTurn.isToolRecordOnly(turn)) {
                    messages.add(TranscriptView.of(turn));
                }
            }
        }
        if (messages.isEmpty()) {
            messages = storedPath(agentId, sessionId);
        }
        if (messages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, NO_SESSION_MESSAGE);
        }
        return new Transcript(sessionId, messages);
    }

    /**
     * The active path of the stored conversation tree, or an empty list when branching is off, the
     * session has no tree, or the tree is not the caller's (its own 404 is folded into ours: the
     * answer is the same "no such conversation" either way). Never throws.
     */
    private List<TranscriptView.Entry> storedPath(UUID agentId, String sessionId) {
        ChatBranchService branchService = branches == null ? null : branches.getIfAvailable();
        if (branchService == null || !branchService.isEnabled()) {
            return List.of();
        }
        try {
            List<TranscriptView.Entry> out = new ArrayList<>();
            for (MessageView m : branchService.activePath(agentId, sessionId).messages()) {
                // The tree keeps no tool records, so a record-only turn it absorbed from the live
                // history is an assistant node with no text: nothing to show.
                if ("assistant".equals(m.role())
                        && !m.error()
                        && (m.content() == null || m.content().isBlank())) {
                    continue;
                }
                out.add(
                        new TranscriptView.Entry(
                                m.role(), m.content(), m.createdAt(), m.status(), m.error()));
            }
            return out;
        } catch (ResponseStatusException | IllegalArgumentException e) {
            // Not the caller's tree, no tree, or an id the tree store does not accept.
            return List.of();
        }
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
