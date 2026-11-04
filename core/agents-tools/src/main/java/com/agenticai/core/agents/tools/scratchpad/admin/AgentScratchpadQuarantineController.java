package com.agenticai.core.agents.tools.scratchpad.admin;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.scratchpad.AgentScratchpadService;
import com.agenticai.core.agents.tools.scratchpad.QuarantinedNoteView;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.tenancy.ListPaging;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Review of scratchpad notes held because the turn that wrote them had handled untrusted content (see
 * {@link AgentScratchpadService#write(UUID, UUID, String, String,
 * com.agenticai.core.agents.tools.scratchpad.NoteTrust)}). A held note is attacker-authored text until a
 * person approves it: approving makes {@code scratchpad_read} / {@code scratchpad_list} return it,
 * rejecting deletes it. Reading the list needs VIEWER on the agent; approving and rejecting need EDITOR
 * (the same level as the agent's other memory approve / reject endpoints) and the {@code
 * PERM_MANAGE_AGENTS} bundle at the edge.
 *
 * <p>A separate base path, not a sub-path of {@code /scratchpad}, because a note key may be any
 * {@code [a-zA-Z0-9_.-]} name and must never be shadowed by a route.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/scratchpad-quarantine")
public class AgentScratchpadQuarantineController {

    private final AgentService agentService;
    private final AgentScratchpadService scratchpadService;

    public AgentScratchpadQuarantineController(
            AgentService agentService, AgentScratchpadService scratchpadService) {
        this.agentService = agentService;
        this.scratchpadService = scratchpadService;
    }

    @GetMapping
    public ResponseEntity<List<QuarantinedNoteView>> list(
            @PathVariable UUID agentId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        try {
            return ListPaging.window(scratchpadService.listQuarantined(agentId), limit, offset, 50, 500);
        } catch (IllegalStateException disabled) {
            throw disabled();
        }
    }

    /**
     * Makes the held note readable. {@code sessionId} is the {@code sessionId} the list returned (omit it
     * for the agent-long-term pad). {@code subject} is the owner the list returned: agent-pad notes are per
     * user, so it is required once two users hold a held note under the same key (400 otherwise). 404 when
     * no held note matches.
     */
    @PostMapping("/{key}/approve")
    public ResponseEntity<Void> approve(
            @PathVariable UUID agentId,
            @PathVariable String key,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) String subject) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        return found(call(() -> scratchpadService.approve(agentId, sessionId, key, subject)));
    }

    /** Deletes the held note; 404 when no held note matches. Never touches a trusted note. */
    @PostMapping("/{key}/reject")
    public ResponseEntity<Void> reject(
            @PathVariable UUID agentId,
            @PathVariable String key,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) String subject) {
        agentService.requireAccess(agentId, PermissionLevel.EDITOR);
        return found(call(() -> scratchpadService.reject(agentId, sessionId, key, subject)));
    }

    private static ResponseEntity<Void> found(boolean existed) {
        return existed ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    private static boolean call(java.util.function.BooleanSupplier action) {
        try {
            return action.getAsBoolean();
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
