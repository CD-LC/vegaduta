package com.agenticai.core.agents.turn;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Renders one recorded turn, or lists the recorded turns of a session. Authorization: PERM_VIEW_AUDIT_LOG
 * (AdminIntegrationRules) plus agent VIEWER.
 */
@RestController
public class TurnJournalController {

    private final TurnJournalService service;

    public TurnJournalController(TurnJournalService service) {
        this.service = service;
    }

    @GetMapping("/api/agents/{agentId}/turns/{turnId}/journal")
    public TurnJournalService.JournalView get(@PathVariable UUID agentId, @PathVariable UUID turnId) {
        return service.view(agentId, turnId);
    }

    /**
     * The journaled turns of one chat session, newest first. A query parameter, not a path segment: a session id is
     * a client-chosen string and may hold characters a path cannot.
     */
    @GetMapping("/api/agents/{agentId}/turn-journal")
    public TurnJournalService.SessionView bySession(
            @PathVariable UUID agentId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return service.listBySession(agentId, sessionId, limit);
    }
}
