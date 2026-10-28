package com.agenticai.core.agents.tools.scratchpad.admin;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.scratchpad.AgentScratchpadService;
import com.agenticai.core.agents.tools.scratchpad.ScratchpadNoteView;
import com.agenticai.core.agents.tools.scratchpad.ScratchpadToolCallback;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.List;
import java.util.Optional;
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
 * <p>{@code session} is the raw caller-visible session id the turn carried (mapped to the session
 * pad's stable UUID exactly as {@link ScratchpadToolCallback#sessionUuid} does). Omitting it, or
 * passing {@code "agent"}, addresses the agent-long-term pad. {@code GET .../scratchpad} lists the
 * visible notes (the session pad merged with the long-term pad when a session is given, matching
 * the agent's own {@code scratchpad_list}); {@code GET .../scratchpad/{key}} returns one note's
 * full body.
 *
 * <p>Lives in a sub-package, not {@code tools.scratchpad} itself: that package must not gain an
 * edge onto {@code agents.core} (it would close a package cycle through {@code agents.tools}).
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

    public record ScratchpadListView(int count, long totalBytes, List<ScratchpadNoteView> notes) {}

    /**
     * Every note visible from {@code session} (the session pad plus the long-term pad), or just the
     * long-term pad when {@code session} is omitted or {@code "agent"}.
     */
    @GetMapping
    public ScratchpadListView list(
            @PathVariable UUID agentId, @RequestParam(required = false) String session) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        List<ScratchpadNoteView> notes = list(agentId, padUuid(session));
        return new ScratchpadListView(
                notes.size(), notes.stream().mapToLong(ScratchpadNoteView::byteSize).sum(), notes);
    }

    /** One note's full body, addressed by the same {@code (session, key)} the tools use. */
    @GetMapping("/{key}")
    public ResponseEntity<ScratchpadNoteView> read(
            @PathVariable UUID agentId,
            @PathVariable String key,
            @RequestParam(required = false) String session) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        return read(agentId, padUuid(session), key)
                .map(ResponseEntity::ok)
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

    private List<ScratchpadNoteView> list(UUID agentId, UUID sessionId) {
        try {
            return scratchpadService.listViews(agentId, sessionId);
        } catch (IllegalStateException disabled) {
            throw disabled();
        }
    }

    private Optional<ScratchpadNoteView> read(UUID agentId, UUID sessionId, String key) {
        try {
            return scratchpadService.readView(agentId, sessionId, key);
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
