package com.agenticai.core.guardrails;

import com.agenticai.core.guardrails.moderation.ModerationReviewController;
import com.agenticai.core.guardrails.moderation.ModerationReviewService;
import com.agenticai.core.guardrails.procedures.ProcedureController;
import com.agenticai.core.guardrails.procedures.ProcedureService;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wave 2 (B) paging: no params keep the complete list; limit/offset window it and send X-Total-Count / X-Has-More. */
class GuardrailListsPagingTest {

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
    void rulesAreWindowedAndHeadered() throws Exception {
        var service = mock(GuardrailRuleService.class);
        when(service.listAll()).thenReturn(List.of());
        assertHttpEmptyPage(MockMvcBuilders.standaloneSetup(new GuardrailRuleController(service)).build(), "/api/guardrails/rules");
    }

    @Test
    void proceduresAreWindowedAndHeadered() throws Exception {
        var service = mock(ProcedureService.class);
        when(service.listAll()).thenReturn(List.of());
        assertHttpEmptyPage(MockMvcBuilders.standaloneSetup(new ProcedureController(service)).build(), "/api/procedures");
    }

    @Test
    void moderationQueueIsWindowedAndHeadered() throws Exception {
        var service = mock(ModerationReviewService.class);
        when(service.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        Authentication auth = mock(Authentication.class);
        when(auth.getAuthorities()).thenAnswer(i -> List.of(new SimpleGrantedAuthority("ROLE_platform-admin")));
        var controller = new ModerationReviewController(service);
        var r = controller.list(null, 5, 3L, auth);
        assertPage(r, 0, "0", "false");
    }

}
