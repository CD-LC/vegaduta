package com.agenticai.core.common.studioapp;

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

/** Wave 2 (B) paging: no params keep the complete list; limit/offset window it and send X-Total-Count / X-Has-More. */
class StudioListsPagingTest {

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
    void projectsAreWindowedAndHeadered() throws Exception {
        var service = mock(StudioProjectService.class);
        when(service.list()).thenReturn(List.of());
        assertHttpEmptyPage(MockMvcBuilders.standaloneSetup(new StudioProjectController(service)).build(), "/api/studio-projects");
    }

    @Test
    void membersAndCommentsAreWindowedAndHeadered() throws Exception {
        var service = mock(StudioCollabService.class);
        when(service.listMembers("p1")).thenReturn(List.of());
        when(service.listComments("p1")).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new StudioCollabController(service)).build();
        assertHttpEmptyPage(mvc, "/api/studio-projects/p1/members");
        assertHttpEmptyPage(mvc, "/api/studio-projects/p1/comments");
    }

    @Test
    void domainsAreWindowedAndHeadered() throws Exception {
        var service = mock(StudioAppDomainService.class);
        when(service.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new StudioAppDomainController(service)).build();
        assertHttpEmptyPage(mvc, "/api/studio-apps/" + java.util.UUID.randomUUID() + "/domains");
    }

}
