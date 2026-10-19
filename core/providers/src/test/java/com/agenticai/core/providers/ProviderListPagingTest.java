package com.agenticai.core.providers;

import com.agenticai.core.providers.dto.ProviderResponse;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Wave 2 (B) paging: no params keep the complete list; limit/offset window it and send X-Total-Count / X-Has-More. */
class ProviderListPagingTest {

    private static <T> List<T> five(Class<T> type) {
        return IntStream.range(0, 5).mapToObj(i -> mock(type)).toList();
    }

    private static void assertPage(ResponseEntity<? extends List<?>> r, int size, String total, String more) {
        assertEquals(size, r.getBody().size());
        assertEquals(total, r.getHeaders().getFirst("X-Total-Count"));
        assertEquals(more, r.getHeaders().getFirst("X-Has-More"));
    }

    private final ProviderService providerService = mock(ProviderService.class);
    private final ProviderModelListingService models = mock(ProviderModelListingService.class);
    private final ProviderController controller = new ProviderController(providerService, models,
            mock(com.agenticai.core.providers.PlatformHostedLlmProviderService.class),
            mock(com.agenticai.core.providers.modality.ModalityCatalogService.class));

    @Test
    void providersPage() {
        when(providerService.listAll()).thenReturn(five(ProviderResponse.class));
        assertPage(controller.list(null, null), 5, "5", "false");
        assertPage(controller.list(2, 1L), 2, "5", "true");
        assertPage(controller.list(2, 4L), 1, "5", "false");
    }

    @Test
    void modelsPage() {
        when(models.listModels(ProviderName.values()[0])).thenReturn(five(ProviderModelResponse.class));
        assertPage(controller.listModels(ProviderName.values()[0].name(), null, null), 5, "5", "false");
        assertPage(controller.listModels(ProviderName.values()[0].name(), 2, 1L), 2, "5", "true");
        assertPage(controller.listModels(ProviderName.values()[0].name(), 2, 4L), 1, "5", "false");
    }
}
