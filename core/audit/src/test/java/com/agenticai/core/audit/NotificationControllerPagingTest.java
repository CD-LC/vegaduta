package com.agenticai.core.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.dto.NotificationResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

/** offset + X-Total-Count / X-Has-More on GET /api/notifications. */
class NotificationControllerPagingTest {

    private final NotificationService service = mock(NotificationService.class);
    private final NotificationController controller = new NotificationController(service);
    private final Authentication member = new TestingAuthenticationToken("u1", "n/a", "ROLE_member");

    private static List<Notification> rows(int n) {
        List<Notification> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new Notification("u1", NotificationCategory.values()[0], "t" + i, "b", NotificationSeverity.values()[0], null, null));
        }
        return out;
    }

    @Test
    void firstShortPageIsTheTotalAndSkipsTheCount() {
        when(service.listVisible(any(), anyBoolean(), anyBoolean(), any(Pageable.class))).thenReturn(rows(3));
        ResponseEntity<List<NotificationResponse>> r = controller.list(false, 20, null, member);
        assertEquals("3", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void fullPageReportsRealTotalAndHasMore() {
        when(service.listVisible(any(), anyBoolean(), anyBoolean(), any(Pageable.class))).thenReturn(rows(2));
        when(service.countVisible("u1", false, false)).thenReturn(9L);
        ResponseEntity<List<NotificationResponse>> r = controller.list(false, 2, 2L, member);
        assertEquals("9", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(service).listVisible(any(), anyBoolean(), anyBoolean(), c.capture());
        assertEquals(2L, c.getValue().getOffset());
        assertEquals(2, c.getValue().getPageSize());
    }

    @Test
    void limitClampedTo200() {
        when(service.listVisible(any(), anyBoolean(), anyBoolean(), any(Pageable.class))).thenReturn(List.of());
        controller.list(false, 9999, 0L, member);
        ArgumentCaptor<Pageable> c = ArgumentCaptor.forClass(Pageable.class);
        verify(service).listVisible(any(), anyBoolean(), anyBoolean(), c.capture());
        assertEquals(200, c.getValue().getPageSize());
    }
}
