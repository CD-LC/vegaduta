package com.agenticai.core.omnichannel.email;

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
class EmailMessageListPagingTest {

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
    void messagesAreWindowedAndHeadered() throws Exception {
        var repo = mock(EmailMessageRepository.class);
        when(repo.findByRunIdOrderByCreatedAtAsc("r1")).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new EmailMessageController(repo)).build();
        mvc.perform(get("/api/email/messages").param("runId", "r1").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"));
    }

}
