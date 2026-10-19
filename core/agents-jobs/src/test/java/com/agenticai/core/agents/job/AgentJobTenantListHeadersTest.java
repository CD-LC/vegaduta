package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.job.dto.AgentJobSummaryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** {@code GET /api/agent-jobs} reports X-Total-Count / X-Has-More without changing the body. */
class AgentJobTenantListHeadersTest {

    private final AgentJobService jobService = mock(AgentJobService.class);
    private final AgentJobController controller = new AgentJobController(jobService);

    @Test
    void firstPageOfManyReportsTotalAndHasMore() {
        when(jobService.listForTenant(any(), isNull(), anyInt(), anyInt())).thenReturn(List.of());
        when(jobService.countForTenant(any(), isNull())).thenReturn(120L);

        ResponseEntity<List<AgentJobSummaryResponse>> r = controller.listForTenant(null, null, 50, 0);

        assertEquals("120", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void lastPageHasNoMoreEvenWhenVisibilityFilteringShortenedIt() {
        when(jobService.listForTenant(any(), isNull(), anyInt(), anyInt())).thenReturn(List.of());
        when(jobService.countForTenant(any(), isNull())).thenReturn(120L);

        ResponseEntity<List<AgentJobSummaryResponse>> r =
                controller.listForTenant(null, null, 50, 100);

        assertEquals("120", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void emptyTenantReportsZero() {
        when(jobService.listForTenant(any(), isNull(), anyInt(), anyInt())).thenReturn(List.of());
        ResponseEntity<List<AgentJobSummaryResponse>> r = controller.listForTenant(null, null, 50, 0);
        assertEquals("0", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }
}
