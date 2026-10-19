package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.dto.AuditLogEntryResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

/** limit/offset + X-Total-Count / X-Has-More on GET /api/audit-log; limit-less callers keep the old 200. */
class AuditLogControllerPagingTest {

    private final AuditLogService service = mock(AuditLogService.class);
    private final AuditLogController controller = new AuditLogController(service);

    private void stubPage(long total) {
        when(service.search(any(), any(), any(), any(), any(), any(), anyLong(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), total));
    }

    @Test
    void neitherParamKeepsLegacyUpTo200AndNoOffset() {
        stubPage(0);
        controller.list(null, null, null, null, null, null, null, null, null);
        verify(service).search(null, null, null, null, null, null, 0L, 200);
    }

    @Test
    void anyParamSwitchesToPagedContractWithDefault50() {
        stubPage(0);
        controller.list(null, null, null, null, null, 10L, null, null, null);
        verify(service).search(null, null, null, null, null, null, 10L, 50);
    }

    @Test
    void limitIsClampedToOneThroughThousandAndOffsetNeverNegative() {
        stubPage(0);
        controller.list("a", "b", null, null, 5000, -5L, null, null, null);
        verify(service).search(eq("a"), eq("b"), any(), any(), any(), any(), eq(0L), eq(1000));
        controller.list(null, null, null, null, 0, 0L, null, null, null);
        verify(service).search(null, null, null, null, null, null, 0L, 1);
    }

    @Test
    void actorAndQAreForwardedToTheService() {
        stubPage(0);
        controller.list(null, null, null, null, null, null, "u1", "login", null);
        verify(service).search(null, null, "u1", "login", null, null, 0L, 200);
    }

    @Test
    void reportsTotalAndHasMoreHeaders() {
        stubPage(120);
        ResponseEntity<List<AuditLogEntryResponse>> r = controller.list(null, null, null, null, 50, 50L, null, null, null);
        assertEquals("120", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void lastPageHasNoMore() {
        when(service.search(any(), any(), any(), any(), any(), any(), anyLong(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        ResponseEntity<List<AuditLogEntryResponse>> r = controller.list(null, null, null, null, 50, 0L, null, null, null);
        assertEquals("0", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }
}
