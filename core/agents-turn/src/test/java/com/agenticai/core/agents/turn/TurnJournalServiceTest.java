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

        TurnJournalService.JournalView v = new TurnJournalService(j, agents).view(agent, turn);

        verify(agents).requireAccess(agent, PermissionLevel.VIEWER);
        assertTrue(v.chainIntact());
        assertEquals(2, v.events().size());
        assertFalse(v.events().isEmpty());
    }

    @Test
    void anUnknownTurnIs404() {
        TurnJournalService s = new TurnJournalService(new InMemoryTurnJournal(), mock(AgentService.class));
        assertThrows(ResponseStatusException.class, () -> s.view(UUID.randomUUID(), UUID.randomUUID()));
    }
}
