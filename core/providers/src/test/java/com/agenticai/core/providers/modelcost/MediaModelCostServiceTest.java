package com.agenticai.core.providers.modelcost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.modelcost.MediaModelCostService.ModelChoice;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MediaModelCostServiceTest {

    private final MediaModelCostRepository repository = mock(MediaModelCostRepository.class);
    private final MediaModelCostService service = new MediaModelCostService(repository);

    private static MediaModelCost row(String modality, String provider, String model, String cost, int rank) {
        MediaModelCost row = mock(MediaModelCost.class);
        when(row.getModality()).thenReturn(modality);
        when(row.getProvider()).thenReturn(provider);
        when(row.getModelId()).thenReturn(model);
        when(row.getTypicalCallCostUsd()).thenReturn(new BigDecimal(cost));
        when(row.getQualityRank()).thenReturn(rank);
        return row;
    }

    @Test
    void cheapestWinsAndQualityRankBreaksTies() {
        List<ModelChoice> ordered = MediaModelCostService.order(List.of(
            new ModelChoice(MediaModality.MUSIC, "ELEVENLABS", "music_v1", new BigDecimal("0.075"), 1),
            new ModelChoice(MediaModality.MUSIC, "ELEVENLABS", "music_v2_5", new BigDecimal("0.075"), 3),
            new ModelChoice(MediaModality.MUSIC, "OTHER", "pricey", new BigDecimal("0.500"), 9),
            new ModelChoice(MediaModality.MUSIC, "OTHER", "bargain", new BigDecimal("0.010"), 0)));

        assertEquals(List.of("bargain", "music_v2_5", "music_v1", "pricey"), ordered.stream().map(ModelChoice::modelId).toList());
    }

    @Test
    void ordersByCostNotByNameSoAKeywordLookingModelDoesNotWinOnItsName() {
        List<MediaModelCost> rows = List.of(
            row("TEXT", "gemini", "gemini-9-flash-lite-mini-haiku", "0.090000", 1),
            row("TEXT", "gemini", "gemini-2.5-pro", "0.003000", 1));
        when(repository.findAllByModalityAndEnabledTrue("TEXT")).thenReturn(rows);

        assertEquals(Optional.of("gemini-2.5-pro"), service.cheapestModel(MediaModality.TEXT, "GEMINI"));
    }

    @Test
    void cheapestAmongOnlyReturnsAListedModelTheCallerActuallyHas() {
        List<MediaModelCost> rows = List.of(
            row("TEXT", "OPENAI", "cheap-but-absent", "0.000100", 1),
            row("TEXT", "OPENAI", "gpt-4o-mini", "0.001050", 1));
        when(repository.findAllByModalityAndEnabledTrue("TEXT")).thenReturn(rows);

        assertEquals(Optional.of("gpt-4o-mini"),
            service.cheapestAmong(MediaModality.TEXT, "OPENAI", List.of("gpt-4o", "gpt-4o-mini")));
        // An unlisted model is never picked by cost - listing IS the quality floor.
        assertEquals(Optional.empty(), service.cheapestAmong(MediaModality.TEXT, "OPENAI", List.of("gpt-4o")));
        assertEquals(Optional.empty(), service.cheapestAmong(MediaModality.TEXT, "OPENAI", List.of()));
    }

    @Test
    void emptyTableFallsBackToBuiltInDefaultsForMedia() {
        when(repository.findAllByModalityAndEnabledTrue("MUSIC")).thenReturn(List.of());
        when(repository.findAllByModalityAndEnabledTrue("IMAGE")).thenReturn(List.of());

        assertEquals(Optional.of("music_v2_5"), service.cheapestModel(MediaModality.MUSIC, "ELEVENLABS"));
        assertEquals("OPENAI", service.cheapestFirst(MediaModality.IMAGE).get(0).provider());
    }

    @Test
    void unreadableTableDegradesInsteadOfThrowing() {
        when(repository.findAllByModalityAndEnabledTrue("IMAGE")).thenThrow(new IllegalStateException("relation does not exist"));

        assertFalse(service.cheapestFirst(MediaModality.IMAGE).isEmpty());
    }

    @Test
    void textHasNoBuiltInDefaultsSoTheOldLogicStaysInCharge() {
        when(repository.findAllByModalityAndEnabledTrue("TEXT")).thenReturn(List.of());

        assertTrue(service.cheapestFirst(MediaModality.TEXT).isEmpty());
    }

    @Test
    void providerRankFollowsCostAndUnlistedProvidersSortLast() {
        List<MediaModelCost> rows = List.of(
            row("IMAGE", "OPENAI", "gpt-image-1", "0.050", 1),
            row("IMAGE", "GOOGLE_VERTEX", "imagen-fast", "0.020", 1));
        when(repository.findAllByModalityAndEnabledTrue("IMAGE")).thenReturn(rows);

        assertEquals(0, service.providerRank(MediaModality.IMAGE, "google_vertex"));
        assertEquals(1, service.providerRank(MediaModality.IMAGE, "OPENAI"));
        assertEquals(Integer.MAX_VALUE, service.providerRank(MediaModality.IMAGE, "NOBODY"));
    }
}
