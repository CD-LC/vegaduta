package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.turnjournal.InMemoryTurnJournal;
import com.agenticai.core.agents.turnjournal.TurnEventType;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TurnJournalServiceTest {

    @Test
    void viewChecksAgentAccessAndReportsChainState() {
        AgentService agents = mock(AgentService.class);
        InMemoryTurnJournal j = new InMemoryTurnJournal();
        UUID agent = UUID.randomUUID();
        UUID turn = UUID.randomUUID();
        j.append(agent, turn, TurnEventType.INTAKE, "h", "s");
        j.append(agent, turn, TurnEventType.COMMIT, "h2", "s2");

        TurnJournalService.JournalView v = new TurnJournalService(j, agents, 100).view(agent, turn);

        verify(agents).requireAccess(agent, PermissionLevel.VIEWER);
        assertTrue(v.chainIntact());
        assertEquals(2, v.events().size());
        assertFalse(v.events().isEmpty());
    }

    @Test
    void anUnknownTurnIs404() {
        TurnJournalService s = new TurnJournalService(new InMemoryTurnJournal(), mock(AgentService.class), 100);
        assertThrows(ResponseStatusException.class, () -> s.view(UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void listBySessionChecksAccessReturnsTheSessionsTurnsAndClampsTheLimit() {
        AgentService agents = mock(AgentService.class);
        InMemoryTurnJournal j = new InMemoryTurnJournal();
        UUID agent = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            UUID turn = UUID.randomUUID();
            j.append(agent, turn, "sess-1", TurnEventType.INTAKE, "h", "s");
            j.append(agent, turn, "sess-1", TurnEventType.COMMIT, "h2", "status=completed answerChars=1");
        }
        TurnJournalService service = new TurnJournalService(j, agents, 3);

        TurnJournalService.SessionView view = service.listBySession(agent, "sess-1", null);

        verify(agents).requireAccess(agent, PermissionLevel.VIEWER);
        assertEquals("sess-1", view.sessionId());
        assertEquals(3, view.turns().size(), "capped at app.turn-journal.max-list");
        assertEquals("completed", view.turns().get(0).status());
        assertEquals(2, service.listBySession(agent, "sess-1", 2).turns().size());
        assertEquals(3, service.listBySession(agent, "sess-1", 500).turns().size());
        assertTrue(service.listBySession(agent, "other", null).turns().isEmpty());
    }

    @Test
    void listBySessionRequiresASession() {
        TurnJournalService s = new TurnJournalService(new InMemoryTurnJournal(), mock(AgentService.class), 100);
        assertThrows(ResponseStatusException.class, () -> s.listBySession(UUID.randomUUID(), " ", null));
    }
}
