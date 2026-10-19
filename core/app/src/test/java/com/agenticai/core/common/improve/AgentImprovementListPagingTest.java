package com.agenticai.core.common.improve;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.core.AgentService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /api/agents/{id}/improvements: array body kept, limit/offset + headers added (wave 2 A). */
class AgentImprovementListPagingTest {

    private static void assertPaged(MockMvc mvc, String path) throws Exception {
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON).param("limit", "5").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get(path).accept(org.springframework.http.MediaType.APPLICATION_JSON).param("limit", "5").param("offset", "999999999999"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void improvements() throws Exception {
        var c =
                new AgentImprovementController(
                        mock(AgentImprovementService.class),
                        mock(AgentHealthScoreService.class),
                        mock(AgentService.class));
        assertPaged(
                MockMvcBuilders.standaloneSetup(c).build(),
                "/api/agents/" + UUID.randomUUID() + "/improvements");
    }
}
