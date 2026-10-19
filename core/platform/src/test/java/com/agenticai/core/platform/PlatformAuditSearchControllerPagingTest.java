package com.agenticai.core.platform;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /api/platform/audit/search: additive offset + X-Total-Count / X-Has-More; no offset = first page as before. */
class PlatformAuditSearchControllerPagingTest {

    private final PlatformAuditSearchService service = mock(PlatformAuditSearchService.class);
    private final MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new PlatformAuditSearchController(service)).build();

    private static PlatformAuditSearchService.SearchResult result(int offset, boolean more, int total) {
        return new PlatformAuditSearchService.SearchResult(List.of(), more, 1, 1, 0, 10, 200, offset, more, total);
    }

    @Test
    void offsetIsForwardedAndHeadersReported() throws Exception {
        when(service.search(any(), eq(10), eq(20))).thenReturn(result(20, true, 31));

        mvc.perform(get("/api/platform/audit/search").param("limit", "10").param("offset", "20")
                .accept(org.springframework.http.MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "31"))
            .andExpect(header().string("X-Has-More", "true"))
            .andExpect(jsonPath("$.offset").value(20))
            .andExpect(jsonPath("$.hasMore").value(true))
            // pre-existing body fields are untouched
            .andExpect(jsonPath("$.rowLimit").value(10))
            .andExpect(jsonPath("$.maxTenants").value(200));
    }

    @Test
    void noOffsetMeansTheFirstPageWithDefaultLimit() throws Exception {
        when(service.search(any(), eq(PlatformAuditSearchService.DEFAULT_LIMIT), eq(0))).thenReturn(result(0, false, 0));

        mvc.perform(get("/api/platform/audit/search"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "0"))
            .andExpect(header().string("X-Has-More", "false"));
        verify(service).search(any(), eq(PlatformAuditSearchService.DEFAULT_LIMIT), eq(0));
    }
}
