package com.agenticai.core.sharing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.sharing.SharedWithMeController.SharedResourceResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Optional limit/offset + X-Total-Count on GET /api/shared-with-me; no params returns everything. */
class SharedWithMeControllerPagingTest {

    private final ResourceAccessService access = mock(ResourceAccessService.class);
    private final SharedWithMeController controller =
            new SharedWithMeController(access, mock(ShareRateLimiter.class));

    private void grants(int n) {
        List<ResourceGrant> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(mock(ResourceGrant.class));
        }
        when(access.listSharedWithMe()).thenReturn(list);
    }

    @Test
    void noParamsReturnsEverythingExactlyAsBefore() {
        grants(7);
        ResponseEntity<List<SharedResourceResponse>> r = controller.sharedWithMe(null, null);
        assertEquals(7, r.getBody().size());
        assertEquals("7", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void limitAndOffsetWindowTheListAndReportMore() {
        grants(7);
        ResponseEntity<List<SharedResourceResponse>> r = controller.sharedWithMe(3, 2L);
        assertEquals(3, r.getBody().size());
        assertEquals("7", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void lastPageAndOutOfRangeOffsetsAreSafe() {
        grants(7);
        assertEquals("false", controller.sharedWithMe(3, 5L).getHeaders().getFirst("X-Has-More"));
        assertEquals(2, controller.sharedWithMe(3, 5L).getBody().size());
        assertEquals(0, controller.sharedWithMe(3, 1_000_000_000L).getBody().size());
        assertEquals(1, controller.sharedWithMe(1, -4L).getBody().size());
        assertEquals(1, controller.sharedWithMe(0, 0L).getBody().size()); // limit clamped to 1
    }
}
