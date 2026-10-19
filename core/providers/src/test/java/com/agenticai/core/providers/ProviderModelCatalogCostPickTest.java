package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.modelcost.MediaModality;
import com.agenticai.core.providers.modelcost.MediaModelCostService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The costed row decides ahead of the keyword scan - and with no row, nothing changes. */
class ProviderModelCatalogCostPickTest {

    private static final List<ProviderModelResponse> OPENAI_MODELS = List.of(
        new ProviderModelResponse("gpt-4o", "GPT-4o", 128_000, true, false, false, null),
        new ProviderModelResponse("gpt-4o-mini", "GPT-4o mini", 128_000, true, false, false, null),
        new ProviderModelResponse("gpt-5.1", "GPT-5.1", 400_000, true, false, false, null));

    private final MediaModelCostService costs = mock(MediaModelCostService.class);
    private ProviderModelCatalogService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenReturn(mock(RestClient.class));
        service = new ProviderModelCatalogService(mock(ProviderModelCatalogRepository.class), builder, mock(PgAdvisoryJobLock.class));
    }

    @Test
    void aCostedRowBeatsTheKeywordPick() {
        service.setMediaModelCostService(costs);
        // Keyword matching would pick "gpt-4o-mini" on its name; the operator costed gpt-5.1 cheaper.
        when(costs.cheapestAmong(eq(MediaModality.TEXT), eq("OPENAI"), anyList())).thenReturn(Optional.of("gpt-5.1"));

        assertEquals(Optional.of("gpt-5.1"), service.costPreferredModel(ProviderName.OPENAI, OPENAI_MODELS));
    }

    @Test
    void onlyModelsActuallyInTheCatalogAreOfferedToTheCostTable() {
        service.setMediaModelCostService(costs);
        when(costs.cheapestAmong(any(), any(), any())).thenReturn(Optional.empty());

        service.costPreferredModel(ProviderName.OPENAI, OPENAI_MODELS);

        org.mockito.Mockito.verify(costs).cheapestAmong(MediaModality.TEXT, "OPENAI", List.of("gpt-4o", "gpt-4o-mini", "gpt-5.1"));
    }

    @Test
    void withNoCostServiceOrNoRowTheExistingPreferenceLogicIsTheWholeAnswer() {
        assertEquals(Optional.empty(), service.costPreferredModel(ProviderName.OPENAI, OPENAI_MODELS));
        assertEquals("gpt-4o-mini", ProviderModelCatalogService.pickDefaultModel(ProviderName.OPENAI, OPENAI_MODELS));

        service.setMediaModelCostService(costs);
        when(costs.cheapestAmong(any(), any(), any())).thenReturn(Optional.empty());
        assertEquals(Optional.empty(), service.costPreferredModel(ProviderName.OPENAI, OPENAI_MODELS));
        assertEquals(Optional.empty(), service.costPreferredModel(ProviderName.OPENAI, List.of()));
    }
}
