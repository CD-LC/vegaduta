package com.agenticai.core.agents.tools.scratchpad;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The operator's read-only window into an agent's scratchpad: what plans and working notes the
 * agent has persisted, per pad. There is deliberately no write/delete here - the pad is
 * agent-authored state; an operator who needs a note gone deletes it through the agent or the
 * database, not by impersonating the agent's own tool surface.
 *
 * <p>{@code session} is the raw caller-visible session id the turn carried (the callback maps it to
 * the session pad's stable UUID exactly as {@link ScratchpadToolCallback#sessionUuid} does).
 * Omitting it addresses the agent-long-term pad. {@code GET .../scratchpad} lists the visible notes
 * (the session pad merged with the long-term pad when a session is given, matching what the agent's
 * own {@code scratchpad_list} would see); {@code GET .../scratchpad/{key}} returns one note's full
 * body.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/scratchpad")
public class AgentScratchpadController {

    private final AgentService agentService;
    private final AgentScratchpadService scratchpadService;

    public AgentScratchpadController(
            AgentService agentService, AgentScratchpadService scratchpadService) {
        this.agentService = agentService;
        this.scratchpadService = scratchpadService;
    }

    /** One note as the operator sees it. {@code scope} mirrors the tool's vocabulary. */
    public record ScratchpadNoteView(
            String key, String scope, int byteSize, Instant updatedAt, String body) {

        static ScratchpadNoteView of(ScratchpadNote note, boolean withBody) {
            return new ScratchpadNoteView(
                    note.getKey(),
                    note.getSessionId() == null ? "agent" : "session",
                    note.getByteSize(),
                    note.getUpdatedAt(),
                    withBody ? note.getBody() : null);
        }
    }

    public record ScratchpadListView(int count, long totalBytes, List<ScratchpadNoteView> notes) {}

    /**
     * Every note visible from {@code session} (the session pad plus the long-term pad), or just the
     * long-term pad when {@code session} is omitted or {@code "agent"}.
     */
    @GetMapping
    public ScratchpadListView list(
            @PathVariable UUID agentId, @RequestParam(required = false) String session) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        List<ScratchpadNote> notes = list(agentId, padUuid(session));
        return new ScratchpadListView(
                notes.size(),
                notes.stream().mapToLong(ScratchpadNote::getByteSize).sum(),
                notes.stream().map(n -> ScratchpadNoteView.of(n, false)).toList());
    }

    /** One note's full body, addressed by the same {@code (session, key)} the tools use. */
    @GetMapping("/{key}")
    public ResponseEntity<ScratchpadNoteView> read(
            @PathVariable UUID agentId,
            @PathVariable String key,
            @RequestParam(required = false) String session) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return read(agentId, padUuid(session), key)
                .map(n -> ResponseEntity.ok(ScratchpadNoteView.of(n, true)))
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND,
                                        "no scratchpad note for key '" + key + "'"));
    }

    /**
     * {@code "agent"} names the long-term pad explicitly; anything else is the raw session id the
     * callback maps.
     */
    private static UUID padUuid(String session) {
        return "agent".equalsIgnoreCase(session == null ? "" : session.trim())
                ? null
                : ScratchpadToolCallback.sessionUuid(session);
    }

    private List<ScratchpadNote> list(UUID agentId, UUID sessionId) {
        try {
            return scratchpadService.list(agentId, sessionId);
        } catch (IllegalStateException disabled) {
            throw disabled();
        }
    }

    private java.util.Optional<ScratchpadNote> read(UUID agentId, UUID sessionId, String key) {
        try {
            return scratchpadService.read(agentId, sessionId, key);
        } catch (IllegalStateException disabled) {
            throw disabled();
        } catch (IllegalArgumentException badKey) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, badKey.getMessage());
        }
    }

    private static ResponseStatusException disabled() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND, "the agent scratchpad is not enabled on this deployment");
    }
}
