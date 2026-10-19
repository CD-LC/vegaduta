package com.agenticai.core.agents.turn;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Renders one recorded turn. Authorization: PERM_VIEW_AUDIT_LOG (AdminIntegrationRules) plus agent VIEWER. */
@RestController
@RequestMapping("/api/agents/{agentId}/turns/{turnId}/journal")
public class TurnJournalController {

    private final TurnJournalService service;

    public TurnJournalController(TurnJournalService service) {
        this.service = service;
    }

    @GetMapping
    public TurnJournalService.JournalView get(@PathVariable UUID agentId, @PathVariable UUID turnId) {
        return service.view(agentId, turnId);
    }
}
