package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turnjournal.TurnEvent;
import com.agenticai.core.agents.turnjournal.TurnHashChain;
import com.agenticai.core.agents.turnjournal.TurnJournal;
import com.agenticai.core.agents.turnjournal.TurnSummary;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read side of the journal: agent access check, rows, chain verification and the turns of a session. */
@Service
public class TurnJournalService {

    public record JournalView(
            UUID agentId,
            UUID turnId,
            boolean chainIntact,
            int brokenAtSeq,
            String verification,
            List<TurnEvent> events) {}

    /** The journaled turns of one chat session, newest first. */
    public record SessionView(UUID agentId, String sessionId, List<TurnSummary> turns) {}

    private final TurnJournal journal;
    private final AgentService agentService;
    private final int maxList;

    public TurnJournalService(
            TurnJournal journal,
            AgentService agentService,
            @Value("${app.turn-journal.max-list:100}") int maxList) {
        this.journal = journal;
        this.agentService = agentService;
        this.maxList = Math.max(1, maxList);
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

    /**
     * The turns the journal holds for one session of one agent, newest first - the way to find a turn without
     * knowing its id. {@code limit} (null = the configured maximum) is clamped to {@code app.turn-journal.max-list}.
     * An empty list, not a 404, when the session has no journaled turn: the journal is off by default, so "nothing
     * recorded" is the ordinary answer.
     */
    public SessionView listBySession(UUID agentId, String sessionId, Integer limit) {
        agentService.requireAccess(agentId, PermissionLevel.VIEWER);
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is required");
        }
        int effective = limit == null ? maxList : Math.max(1, Math.min(limit, maxList));
        return new SessionView(agentId, sessionId, journal.listBySession(agentId, sessionId, effective));
    }
}
