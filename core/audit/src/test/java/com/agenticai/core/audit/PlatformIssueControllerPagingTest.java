package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.dto.PlatformIssueResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** limit/offset + X-Total-Count / X-Has-More on GET /api/admin/issues; param-less callers keep the old 200. */
class PlatformIssueControllerPagingTest {

    private final PlatformIssueService service = mock(PlatformIssueService.class);
    private final PlatformIssueController controller = new PlatformIssueController(service);

    @Test
    void noParamsKeepsTheLegacyNewest200() {
        when(service.list(isNull(), anyInt(), anyLong())).thenReturn(List.of());
        controller.list(null, null, null);
        verify(service).list(null, 200, 0L);
    }

    @Test
    void anyParamSwitchesToPagedContractWithDefault50AndClamps() {
        when(service.list(isNull(), anyInt(), anyLong())).thenReturn(List.of());
        controller.list(null, null, 10L);
        verify(service).list(null, 50, 10L);
        controller.list(null, 5000, -3L);
        verify(service).list(null, 1000, 0L);
    }

    @Test
    void statusFilterIsPassedThroughAndCounted() {
        when(service.list(eq(IssueStatus.OPEN), anyInt(), anyLong())).thenReturn(List.of());
        when(service.count(IssueStatus.OPEN)).thenReturn(120L);
        ResponseEntity<List<PlatformIssueResponse>> r = controller.list(IssueStatus.OPEN, 50, 50L);
        assertEquals("120", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void emptyFirstPageIsItsOwnTotal() {
        when(service.list(isNull(), anyInt(), anyLong())).thenReturn(List.of());
        ResponseEntity<List<PlatformIssueResponse>> r = controller.list(null, null, null);
        assertEquals("0", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }
}
