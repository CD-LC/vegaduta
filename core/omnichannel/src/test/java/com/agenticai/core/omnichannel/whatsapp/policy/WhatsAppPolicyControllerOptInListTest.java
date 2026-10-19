package com.agenticai.core.omnichannel.whatsapp.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.whatsapp.policy.WhatsAppPolicyController.OptInListItemResponse;
import com.agenticai.core.omnichannel.whatsapp.policy.WhatsAppSendGuard.OptInListItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** GET /api/channels/whatsapp-compliance/opt-in: paging, totals, and the fields exposed. */
class WhatsAppPolicyControllerOptInListTest {

    private final WhatsAppSendGuard guard = mock(WhatsAppSendGuard.class);
    private final WhatsAppPolicyController controller =
            new WhatsAppPolicyController(guard, mock(WhatsAppDispatchService.class));

    @Test
    void mapsRowsWithoutEvidenceAndReportsTotals() {
        Instant t = Instant.parse("2026-10-14T10:00:00Z");
        when(guard.listOptIns(false, 50L, 50))
                .thenReturn(List.of(new OptInListItem("15551234567", "web_form", true, t, null)));
        when(guard.countOptIns(false)).thenReturn(120L);

        ResponseEntity<List<OptInListItemResponse>> r = controller.listOptIns(null, 50L, false);

        assertEquals(1, r.getBody().size());
        OptInListItemResponse row = r.getBody().get(0);
        assertEquals("15551234567", row.phoneDigits());
        assertEquals("web_form", row.source());
        assertEquals(t, row.consentedAt());
        assertEquals("120", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void limitIsClampedAndActiveOnlyIsForwarded() {
        when(guard.listOptIns(true, 0L, 500)).thenReturn(List.of());

        ResponseEntity<List<OptInListItemResponse>> r = controller.listOptIns(99999, null, true);

        verify(guard).listOptIns(true, 0L, 500);
        assertEquals("0", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void negativeOffsetAndZeroLimitAreClamped() {
        when(guard.listOptIns(false, 0L, 1)).thenReturn(List.of());
        controller.listOptIns(0, -5L, false);
        verify(guard).listOptIns(false, 0L, 1);
    }
}
