package com.agenticai.core.agents.turn;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.turnjournal.TurnSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The two read routes of the turn journal: by turn id, and the by-session listing (a query parameter). */
class TurnJournalControllerTest {

    private final TurnJournalService service = mock(TurnJournalService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TurnJournalController(service)).build();
    private final UUID agent = UUID.randomUUID();

    @Test
    void aSessionsTurnsAreListedByQueryParameter() throws Exception {
        UUID turn = UUID.randomUUID();
        when(service.listBySession(agent, "weird:session/id", 5))
                .thenReturn(
                        new TurnJournalService.SessionView(
                                agent,
                                "weird:session/id",
                                List.of(new TurnSummary(turn, agent, Instant.now(), Instant.now(), 3, "completed"))));

        mvc.perform(get("/api/agents/{agent}/turn-journal", agent).param("sessionId", "weird:session/id").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("weird:session/id"))
                .andExpect(jsonPath("$.turns[0].turnId").value(turn.toString()))
                .andExpect(jsonPath("$.turns[0].status").value("completed"));
    }

    @Test
    void theSessionIdIsRequired() throws Exception {
        mvc.perform(get("/api/agents/{agent}/turn-journal", agent)).andExpect(status().isBadRequest());
    }

    @Test
    void oneTurnIsStillReadByItsId() throws Exception {
        UUID turn = UUID.randomUUID();
        when(service.view(agent, turn))
                .thenReturn(new TurnJournalService.JournalView(agent, turn, true, -1, "ok", List.of()));

        mvc.perform(get("/api/agents/{agent}/turns/{turn}/journal", agent, turn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chainIntact").value(true));
        verify(service).view(eq(agent), eq(turn));
    }
}
