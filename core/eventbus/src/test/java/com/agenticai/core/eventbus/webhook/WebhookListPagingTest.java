package com.agenticai.core.eventbus.webhook;

import com.agenticai.core.eventbus.webhook.WebhookSubscriptionService.SubscriptionView;
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
class WebhookListPagingTest {

    private static <T> List<T> five(Class<T> type) {
        return IntStream.range(0, 5).mapToObj(i -> mock(type)).toList();
    }

    private static void assertPage(ResponseEntity<? extends List<?>> r, int size, String total, String more) {
        assertEquals(size, r.getBody().size());
        assertEquals(total, r.getHeaders().getFirst("X-Total-Count"));
        assertEquals(more, r.getHeaders().getFirst("X-Has-More"));
    }

    private final WebhookSubscriptionService service = mock(WebhookSubscriptionService.class);
    private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
    private final WebhookSubscriptionController controller = new WebhookSubscriptionController(service, deliveries);

    @Test
    void subscriptionsPageAndNoParamsKeepEverything() {
        when(service.list()).thenReturn(five(SubscriptionView.class));
        assertPage(controller.list(null, null), 5, "5", "false");
        assertPage(controller.list(2, 1L), 2, "5", "true");
        assertPage(controller.list(2, 4L), 1, "5", "false");
    }

    @Test
    void eventTypeCatalogIsPagedOverHttp() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        int all = WebhookEventCatalog.ENTRIES.size();
        mvc.perform(get("/api/webhooks/event-types").param("limit", "1").param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", String.valueOf(all)))
                .andExpect(header().string("X-Has-More", String.valueOf(all > 1)));
        mvc.perform(get("/api/webhooks/event-types"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void deliveriesAreWindowedAndHeaderedEvenWhenEmpty() throws Exception {
        when(deliveries.findBySubscriptionIdOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/webhooks/" + java.util.UUID.randomUUID() + "/deliveries").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
    }
}
