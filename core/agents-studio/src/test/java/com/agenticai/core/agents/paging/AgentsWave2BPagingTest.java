package com.agenticai.core.agents.paging;

import com.agenticai.core.agents.skills.SkillRegistryClient;
import com.agenticai.core.agents.skills.SkillSupplyChainController;
import com.agenticai.core.agents.skills.SkillSupplyChainService;
import com.agenticai.core.agents.skills.SkillSupplyChainService.SkillReviewItem;
import com.agenticai.core.agents.tools.code.CustomCodeToolController;
import com.agenticai.core.agents.tools.code.CustomCodeToolService;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSourceController;
import com.agenticai.core.agents.tools.openapi.OpenApiToolSourceService;
import com.agenticai.core.agents.tools.script.UserToolScriptController;
import com.agenticai.core.agents.tools.script.UserToolScriptService;
import com.agenticai.core.tables.DataTableController;
import com.agenticai.core.tables.DataTableDtos.TableView;
import com.agenticai.core.tables.DataTableService;
import com.agenticai.core.tutor.StudyPlanController;
import com.agenticai.core.tutor.StudyPlanService;
import com.agenticai.core.tutor.dto.TutorDtos.PlanSummary;
import java.security.Principal;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wave 2 (B) paging: no params keep the complete list; limit/offset window it and send X-Total-Count / X-Has-More.
 * The scheduled-task list's case lives in core/agents-jobs ({@code JobsListPagingTest}).
 */
class AgentsWave2BPagingTest {

    private static <T> List<T> five(Class<T> type) {
        return IntStream.range(0, 5).mapToObj(i -> mock(type)).toList();
    }

    private static void assertPage(ResponseEntity<? extends List<?>> r, int size, String total, String more) {
        assertEquals(size, r.getBody().size());
        assertEquals(total, r.getHeaders().getFirst("X-Total-Count"));
        assertEquals(more, r.getHeaders().getFirst("X-Has-More"));
    }

    /** Param binding + headers over HTTP for an endpoint whose backing list is empty. */
    private static void assertHttpEmptyPage(org.springframework.test.web.servlet.MockMvc mvc, String url) throws Exception {
        mvc.perform(get(url).param("limit", "5").param("offset", "3"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void dataTablesPage() {
        var service = mock(DataTableService.class);
        when(service.list()).thenReturn(five(TableView.class));
        var controller = new DataTableController(service);
        assertPage(controller.list(null, null), 5, "5", "false");
        assertPage(controller.list(2, 1L), 2, "5", "true");
        assertPage(controller.list(2, 4L), 1, "5", "false");
    }

    @Test
    void skillSecurityReviewsPage() {
        var service = mock(SkillSupplyChainService.class);
        when(service.reviews()).thenReturn(five(SkillReviewItem.class));
        var controller = new SkillSupplyChainController(service, mock(SkillRegistryClient.class));
        assertPage(controller.reviews(null, null), 5, "5", "false");
        assertPage(controller.reviews(2, 1L), 2, "5", "true");
        assertPage(controller.reviews(2, 4L), 1, "5", "false");
    }

    @Test
    void tutorPlansPage() {
        var service = mock(StudyPlanService.class);
        Principal p = () -> "u1";
        when(service.list(org.mockito.ArgumentMatchers.any())).thenReturn(five(PlanSummary.class));
        var controller = new StudyPlanController(service, null, false, 10);
        assertPage(controller.list(p, null, null), 5, "5", "false");
        assertPage(controller.list(p, 2, 1L), 2, "5", "true");
        assertPage(controller.list(p, 2, 4L), 1, "5", "false");
    }

    @Test
    void toolListsAreWindowedAndHeadered() throws Exception {
        var code = mock(CustomCodeToolService.class);
        when(code.listAll()).thenReturn(List.of());
        assertHttpEmptyPage(MockMvcBuilders.standaloneSetup(new CustomCodeToolController(code)).build(), "/api/tools/code");
        var openapi = mock(OpenApiToolSourceService.class);
        when(openapi.listAll()).thenReturn(List.of());
        when(openapi.listTools(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        var openapiMvc = MockMvcBuilders.standaloneSetup(new OpenApiToolSourceController(openapi)).build();
        assertHttpEmptyPage(openapiMvc, "/api/tools/openapi");
        assertHttpEmptyPage(openapiMvc, "/api/tools/openapi/" + java.util.UUID.randomUUID() + "/tools");
        var scripts = mock(UserToolScriptService.class);
        when(scripts.listAll()).thenReturn(List.of());
        assertHttpEmptyPage(MockMvcBuilders.standaloneSetup(new UserToolScriptController(scripts)).build(), "/api/tools/scripts");
    }

}
