package com.agenticai.core.agents.core;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * GET /api/agents: the body is still a plain array and every request now carries the headers. The
 * slicing itself (limit, offset, clamping, has-more) is pinned in ListPagingTest.window* with the
 * same helper; here the wiring (parameter binding, headers, no-param default) is pinned.
 */
class AgentListPagingTest {

    private final AgentService service = mock(AgentService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AgentController(service, null, null)).build();

    @Test
    void noParamsKeepsTheArrayBodyAndAddsTheTotal() throws Exception {
        when(service.listAll()).thenReturn(List.of());
        mvc.perform(get("/api/agents"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void windowParamsBindAndAHugeOffsetIsAnEmptyPageNotAnError() throws Exception {
        when(service.listAll()).thenReturn(List.of());
        mvc.perform(get("/api/agents").param("limit", "20").param("offset", "999999999999"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"));
    }
}
