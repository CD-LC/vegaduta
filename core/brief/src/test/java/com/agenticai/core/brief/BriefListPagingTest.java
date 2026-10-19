package com.agenticai.core.brief;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.security.TenantLimits;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /api/briefs and /api/briefs/{id}/versions: additive limit/offset + headers (wave 2 A). */
class BriefListPagingTest {

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
    void briefs() throws Exception {
        var c =
                new BriefController(
                        mock(BriefPlannerService.class),
                        mock(BriefReadinessService.class),
                        mock(BriefService.class),
                        mock(BriefRateLimiter.class),
                        mock(TenantLimits.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/briefs");
    }

    @Test
    void versions() throws Exception {
        var c =
                new BriefLifecycleController(
                        mock(BriefApplyService.class),
                        mock(BriefVersionService.class),
                        mock(BriefTuneService.class),
                        mock(TrustLadderService.class),
                        mock(BriefRateLimiter.class),
                        mock(TenantLimits.class));
        assertPaged(
                MockMvcBuilders.standaloneSetup(c).build(),
                "/api/briefs/" + UUID.randomUUID() + "/versions");
    }
}
