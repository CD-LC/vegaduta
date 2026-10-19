package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turnjournal.TurnEvent;
import com.agenticai.core.agents.turnjournal.TurnHashChain;
import com.agenticai.core.agents.turnjournal.TurnJournal;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read side of the journal: agent access check, rows, and chain verification. */
@Service
public class TurnJournalService {

    public record JournalView(
            UUID agentId,
            UUID turnId,
            boolean chainIntact,
            int brokenAtSeq,
            String verification,
            List<TurnEvent> events) {}

    private final TurnJournal journal;
    private final AgentService agentService;

    public TurnJournalService(TurnJournal journal, AgentService agentService) {
        this.journal = journal;
        this.agentService = agentService;
    }

    public JournalView view(UUID agentId, UUID turnId) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        List<TurnEvent> events = journal.read(agentId, turnId);
        if (events.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No journal for this turn");
        }
        TurnHashChain.Verification v = TurnHashChain.verify(events);
        return new JournalView(agentId, turnId, v.intact(), v.brokenAtSeq(), v.reason(), events);
    }
}
