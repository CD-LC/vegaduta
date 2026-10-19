package com.agenticai.core.agents.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /api/agents/{id}/jobs: same body and defaults as before, plus X-Total-Count / X-Has-More. */
class AgentJobListPagingTest {

    private final AgentJobService service = mock(AgentJobService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentJobController(service)).build();
    private final UUID agent = UUID.randomUUID();

    @Test
    void defaultsAreFiftyFromZeroAndTheTotalIsReported() throws Exception {
        when(service.listForAgent(eq(agent), any(), eq(50), eq(0))).thenReturn(List.of());
        when(service.countForTenant(any(), eq(agent))).thenReturn(120L);
        mvc.perform(get("/api/agents/{id}/jobs", agent))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "120"))
                .andExpect(header().string("X-Has-More", "true"));
        verify(service).listForAgent(agent, Set.of(), 50, 0);
    }

    @Test
    void lastPageHasNoMore() throws Exception {
        when(service.listForAgent(eq(agent), any(), eq(50), eq(100))).thenReturn(List.of());
        when(service.countForTenant(any(), eq(agent))).thenReturn(120L);
        mvc.perform(get("/api/agents/{id}/jobs", agent).param("offset", "100"))
                .andExpect(header().string("X-Total-Count", "120"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void statusFilterIsPassedToTheCount() throws Exception {
        when(service.listForAgent(eq(agent), any(), eq(10), eq(0))).thenReturn(List.of());
        when(service.countForTenant(any(), eq(agent))).thenReturn(4L);
        mvc.perform(get("/api/agents/{id}/jobs", agent).param("status", "FAILED").param("limit", "10"))
                .andExpect(header().string("X-Total-Count", "4"))
                .andExpect(header().string("X-Has-More", "false"));
        verify(service).countForTenant(Set.of(AgentJobStatus.FAILED), agent);
    }
}
