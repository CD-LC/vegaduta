package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class PlatformBillingControllerTest {

    private final PlatformBillingService service = mock(PlatformBillingService.class);
    private final PlatformBillingController controller = new PlatformBillingController(service);

    @Test
    void delegatesEveryReadToTheService() {
        var summary =
                new PlatformBillingService.BillingSummary(
                        0, Map.of(), Map.of(), 0, 0, 0, 0, 0, 0, List.of());
        when(service.summary()).thenReturn(summary);
        assertSame(summary, controller.summary());

        var subs = new PlatformBillingService.SubscriptionList(0, false, List.of());
        when(service.subscriptions("PREMIUM", "PAID", "ac", 10)).thenReturn(subs);
        assertSame(subs, controller.subscriptions("PREMIUM", "PAID", "ac", 10));

        var grants = new PlatformBillingService.CompGrantList(0, List.of());
        when(service.compGrants(5)).thenReturn(grants);
        assertSame(grants, controller.compGrants(5));

        var inv = new PlatformBillingService.InvoiceList(0, List.of());
        when(service.invoices("t", 3)).thenReturn(inv);
        assertSame(inv, controller.invoices("t", 3));
        verify(service).invoices("t", 3);
    }

    @Test
    void controllerIsReadOnlyUnderPlatformPrefix() {
        org.junit.jupiter.api.Assertions.assertEquals(
                "/api/platform/billing",
                PlatformBillingController.class.getAnnotation(RequestMapping.class).value()[0]);
        for (var m : PlatformBillingController.class.getDeclaredMethods()) {
            if (m.isSynthetic()) {
                continue; // e.g. JaCoCo's $jacocoInit under -Pcoverage
            }
            org.junit.jupiter.api.Assertions.assertNotNull(
                    m.getAnnotation(GetMapping.class),
                    "non-GET handler on read-only billing controller: " + m.getName());
        }
    }
}
