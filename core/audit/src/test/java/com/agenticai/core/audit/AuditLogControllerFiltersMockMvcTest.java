package com.agenticai.core.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** GET /api/audit-log over HTTP: the new {@code actor} and {@code q} params, with the old defaults intact. */
class AuditLogControllerFiltersMockMvcTest {

    private final AuditLogService service = mock(AuditLogService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditLogController(service)).build();

    @Test
    void actorAndQAreForwardedAlongsideTheExistingFilters() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), any(), anyLong(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 45));

        mvc.perform(get("/api/audit-log")
                .param("actionType", "agent.created").param("actor", "user-7").param("q", "50%_off")
                .param("limit", "20").param("offset", "20"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Total-Count", "45"))
            .andExpect(header().string("X-Has-More", "true"));

        verify(service).search(eq("agent.created"), isNull(), eq("user-7"), eq("50%_off"), isNull(), isNull(),
            eq(20L), eq(20));
    }

    @Test
    void withoutActorOrQTheRequestBehavesAsBefore() throws Exception {
        when(service.search(any(), any(), any(), any(), any(), any(), anyLong(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

        mvc.perform(get("/api/audit-log")).andExpect(status().isOk());

        verify(service).search(isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(0L), eq(200));
    }
}
