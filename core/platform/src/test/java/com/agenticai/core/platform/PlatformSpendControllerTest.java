package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PlatformSpendControllerTest {

    private final PlatformSpendService service = mock(PlatformSpendService.class);
    private final PlatformSpendController controller = new PlatformSpendController(service);

    @Test
    void exportReturnsCsvAttachmentWithGroupInFilename() {
        when(service.exportCsv(eq("day"), any(), eq(null))).thenReturn("a,b\n1,2\n");
        var res =
                controller.exportCsv(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), " DAY ", null);
        assertEquals("a,b\n1,2\n", new String(res.getBody(), StandardCharsets.UTF_8));
        String cd = res.getHeaders().getFirst("Content-Disposition");
        assertTrue(cd.contains("platform-spend-day-2026-09-01_to_2026-09-05.csv"));
        assertTrue(res.getHeaders().getContentType().toString().startsWith("text/csv"));
    }

    @Test
    void exportRejectsUnknownGroup() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        controller.exportCsv(
                                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), "agent", null));
    }

    @Test
    void overLongRangeIsRejectedBeforeAnySweep() {
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.summary(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 9, 1), null));
    }

    @Test
    void tenantIdIsPassedThroughToTheService() {
        java.util.UUID tenantId = java.util.UUID.randomUUID();
        controller.byDay(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), tenantId);
        org.mockito.Mockito.verify(service).byDay(any(), eq(tenantId));
        when(service.exportCsv(eq("tenant"), any(), eq(tenantId))).thenReturn("x\n");
        var res =
                controller.exportCsv(
                        LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), "tenant", tenantId);
        assertEquals("x\n", new String(res.getBody(), StandardCharsets.UTF_8));
    }
}
