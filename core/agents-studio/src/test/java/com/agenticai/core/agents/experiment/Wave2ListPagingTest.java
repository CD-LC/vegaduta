package com.agenticai.core.agents.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.face.FaceEnrollmentController;
import com.agenticai.core.agents.face.FaceEnrollmentService;
import com.agenticai.core.agents.tools.x402.X402OfferController;
import com.agenticai.core.agents.tools.x402.X402OfferRepository;
import com.agenticai.core.agents.trust.DelegationController;
import com.agenticai.core.agents.trust.DelegationTokenService;
import com.agenticai.core.agents.version.AgentVersionController;
import com.agenticai.core.agents.version.AgentVersionResponse;
import com.agenticai.core.agents.version.AgentVersionService;
import com.agenticai.core.tables.DataTableController;
import com.agenticai.core.tables.DataTableService;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Wave 2 (A) list paging: the agent-scoped and console lists keep the plain-array body and add
 * limit/offset + X-Total-Count + X-Has-More. No params = the whole list, as before. The event
 * trigger list's case lives in core/agents-jobs ({@code JobsListPagingTest}), the browser profile
 * and recipe lists' in core/agents-browser ({@code BrowserListPagingTest}).
 */
class Wave2ListPagingTest {

    private static final String ID = UUID.randomUUID().toString();
    private final AgentService agentService = mock(AgentService.class);

    private static void assertPaged(MockMvc mvc, String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
        mvc.perform(get(path).param("limit", "5").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get(path).param("limit", "5").param("offset", "999999999999"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void versions() throws Exception {
        var svc = mock(AgentVersionService.class);
        var controller = new AgentVersionController(svc);
        assertPaged(MockMvcBuilders.standaloneSetup(controller).build(), "/api/agents/" + ID + "/versions");

        var rows = IntStream.range(0, 7).mapToObj(i -> mock(AgentVersionResponse.class)).toList();
        UUID agent = UUID.randomUUID();
        when(svc.list(agent)).thenReturn(rows);
        ResponseEntity<List<AgentVersionResponse>> all = controller.list(agent, null, null);
        assertEquals(7, all.getBody().size());
        assertEquals("7", all.getHeaders().getFirst("X-Total-Count"));
        ResponseEntity<List<AgentVersionResponse>> page = controller.list(agent, 3, 2L);
        assertEquals(3, page.getBody().size());
        assertEquals("true", page.getHeaders().getFirst("X-Has-More"));
        assertEquals(0, controller.list(agent, 3, 1_000_000_000L).getBody().size());
    }

    @Test
    void experiments() throws Exception {
        var c = new AgentExperimentController(mock(AgentExperimentService.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/agents/" + ID + "/experiments");
    }

    @Test
    void x402Offers() throws Exception {
        var c = new X402OfferController(mock(X402OfferRepository.class), null, agentService);
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/agents/" + ID + "/x402/offers");
    }

    @Test
    void delegations() throws Exception {
        var c = new DelegationController(mock(DelegationTokenService.class), agentService, null, null);
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/agents/" + ID + "/delegations");
    }

    @Test
    void dataTables() throws Exception {
        var c = new DataTableController(mock(DataTableService.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/data-tables");
    }

    @Test
    void faceEnrollments() throws Exception {
        var c = new FaceEnrollmentController(mock(FaceEnrollmentService.class), agentService);
        var mvc = MockMvcBuilders.standaloneSetup(c).build();
        mvc.perform(get("/api/face-enrollments").param("agentId", ID).param("collectionId", ID))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"));
        mvc.perform(get("/api/face-enrollments").param("agentId", ID).param("collectionId", ID)
                        .param("limit", "10").param("offset", "4"))
                .andExpect(status().isOk());
    }
}
