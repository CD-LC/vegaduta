package com.agenticai.core.elevenlabs.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.elevenlabs.ElevenLabsApiException;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.elevenlabs.ElevenLabsCredentialService;
import com.agenticai.core.elevenlabs.GeneratedMusic;
import com.agenticai.core.elevenlabs.GeneratedMusicService;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.modelcost.MediaModality;
import com.agenticai.core.providers.modelcost.MediaModelCostService;
import com.agenticai.core.providers.modelcost.MediaModelCostService.ModelChoice;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MusicGenerationServiceTest {

    private static final byte[] MP3 = {1, 2, 3};

    private final ElevenLabsClient client = mock(ElevenLabsClient.class);
    private final ElevenLabsCredentialService byok = mock(ElevenLabsCredentialService.class);
    private final PlatformToolCredentialService platform =
            mock(PlatformToolCredentialService.class);
    private final GeneratedMusicService store = mock(GeneratedMusicService.class);
    private final MusicGenerationQuotaService quota = mock(MusicGenerationQuotaService.class);
    private final MediaModelCostService costs = mock(MediaModelCostService.class);
    private MusicGenerationService service;

    @BeforeEach
    void setUp() {
        service = new MusicGenerationService(client, byok, platform, store, quota, costs);
        when(quota.maxDurationSeconds()).thenReturn(30);
        when(costs.cheapestFirst(MediaModality.MUSIC, "ELEVENLABS"))
                .thenReturn(
                        List.of(
                                new ModelChoice(
                                        MediaModality.MUSIC,
                                        "ELEVENLABS",
                                        "music_v2_5",
                                        new BigDecimal("0.075"),
                                        3),
                                new ModelChoice(
                                        MediaModality.MUSIC,
                                        "ELEVENLABS",
                                        "music_v1",
                                        new BigDecimal("0.075"),
                                        1)));
        when(store.store(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(UUID.randomUUID());
    }

    private void platformKeyOnly() {
        when(byok.isActive()).thenReturn(false);
        when(platform.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.of("platform-key"));
    }

    @Test
    void availableWithEitherKeyAndUnavailableWithNeither() {
        when(platform.resolveActiveApiKey(PlatformToolKey.ELEVENLABS)).thenReturn(Optional.empty());
        assertFalse(service.isAvailable());
        when(byok.isActive()).thenReturn(true);
        assertTrue(service.isAvailable());
    }

    @Test
    void platformPathChecksQuotaBeforeTheBillableCallAndBillsThePlatform() {
        platformKeyOnly();
        when(client.composeMusic(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
                .thenReturn(MP3);

        MusicGenerationService.GeneratedTrack track = service.generate("calm piano", 10, true);

        var order = org.mockito.Mockito.inOrder(quota, client);
        order.verify(quota).checkAllowed();
        order.verify(client).composeMusic("calm piano", 10_000, "music_v2_5", true, "platform-key");
        verify(store)
                .store(
                        MP3,
                        "calm piano",
                        "music_v2_5",
                        "ELEVENLABS",
                        GeneratedMusic.BILLED_TO_PLATFORM,
                        10);
        assertTrue(track.platformBilled());
        assertEquals("music_v2_5", track.model());
    }

    @Test
    void overQuotaMeansNoProviderCallAtAll() {
        platformKeyOnly();
        doThrow(new MusicGenerationQuotaExceededException("limit")).when(quota).checkAllowed();

        assertThrows(
                MusicGenerationQuotaExceededException.class,
                () -> service.generate("calm piano", 10, false));

        verifyNoInteractions(client);
        verifyNoInteractions(store);
    }

    @Test
    void tenantsOwnKeyIsUsedFirstAndIsNotCountCapped() {
        when(byok.isActive()).thenReturn(true);
        when(byok.resolveActiveApiKey()).thenReturn("tenant-key");
        when(client.composeMusic(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
                .thenReturn(MP3);

        MusicGenerationService.GeneratedTrack track = service.generate("calm piano", 10, false);

        verify(client).composeMusic("calm piano", 10_000, "music_v2_5", false, "tenant-key");
        verify(quota, never()).checkAllowed();
        verify(quota).checkEnabled();
        verify(store)
                .store(
                        MP3,
                        "calm piano",
                        "music_v2_5",
                        "ELEVENLABS",
                        GeneratedMusic.BILLED_TO_TENANT,
                        10);
        assertFalse(track.platformBilled());
    }

    @Test
    void durationIsClampedToTheTierMaximumAndDefaultsWhenMissing() {
        platformKeyOnly();
        when(client.composeMusic(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
                .thenReturn(MP3);

        assertEquals(30, service.generate("epic score", 600, false).durationSeconds());
        assertEquals(20, service.generate("epic score", null, false).durationSeconds());
        assertEquals(3, MusicGenerationService.clampSeconds(1, 30));
    }

    @Test
    void aRejectedModelFallsThroughToTheNextListedOne() {
        platformKeyOnly();
        when(client.composeMusic(
                        anyString(), anyInt(), eq("music_v2_5"), anyBoolean(), anyString()))
                .thenThrow(new ElevenLabsApiException(422, "unknown model"));
        when(client.composeMusic(anyString(), anyInt(), eq("music_v1"), anyBoolean(), anyString()))
                .thenReturn(MP3);

        assertEquals("music_v1", service.generate("calm piano", 10, false).model());
    }

    @Test
    void authPaymentAndRateLimitFailuresStopImmediately() {
        platformKeyOnly();
        when(client.composeMusic(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new ElevenLabsApiException(401, "bad key"));

        assertThrows(ElevenLabsApiException.class, () -> service.generate("calm piano", 10, false));

        verify(client, times(1))
                .composeMusic(anyString(), anyInt(), anyString(), anyBoolean(), anyString());
        assertFalse(MusicGenerationService.isWorthTryingNextModel(402));
        assertFalse(MusicGenerationService.isWorthTryingNextModel(429));
        assertFalse(MusicGenerationService.isWorthTryingNextModel(0));
    }

    @Test
    void noKeyAnywhereIsANotConfiguredError() {
        when(byok.isActive()).thenReturn(false);
        when(platform.resolveActiveApiKey(PlatformToolKey.ELEVENLABS)).thenReturn(Optional.empty());

        assertThrows(
                MusicGenNotConfiguredException.class,
                () -> service.generate("calm piano", 10, false));
    }
}
