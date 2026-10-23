package com.agenticai.core.agents.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.demo.DemoMusicController.DemoMusicRequest;
import com.agenticai.core.agents.demo.DemoMusicController.DemoMusicResponse;
import com.agenticai.core.agentspi.MusicComposer;
import com.agenticai.core.elevenlabs.ElevenLabsApiException;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.elevenlabs.agenttools.ElevenLabsMusicComposer;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.modelcost.MediaModality;
import com.agenticai.core.providers.modelcost.MediaModelCostService;
import com.agenticai.core.providers.modelcost.MediaModelCostService.ModelChoice;
import com.agenticai.core.tenancy.demo.DemoMusicService;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRateLimiter;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The contract that matters on an unauthenticated, platform-paid endpoint: every guard runs BEFORE
 * the billable call, in the same order {@code /api/demo/image} uses, and nothing ever surfaces as
 * an error.
 */
class DemoMusicControllerTest {

    private static final String IP = "203.0.113.7";
    private static final byte[] MP3 = {9, 9, 9};

    private final DemoSessionService sessions = mock(DemoSessionService.class);
    private final DemoMusicService music = mock(DemoMusicService.class);
    private final DemoSessionRateLimiter limiter = mock(DemoSessionRateLimiter.class);
    private final ElevenLabsClient client = mock(ElevenLabsClient.class);
    private final PlatformToolCredentialService credentials =
            mock(PlatformToolCredentialService.class);
    private final MediaModelCostService costs = mock(MediaModelCostService.class);
    private final HttpServletRequest servletRequest = mock(HttpServletRequest.class);
    private DemoMusicController controller;
    private DemoSession session;

    @BeforeEach
    void setUp() {
        controller =
                new DemoMusicController(
                        sessions,
                        music,
                        limiter,
                        providerOf(new ElevenLabsMusicComposer(client)),
                        credentials,
                        costs);
        Instant now = Instant.now();
        session =
                new DemoSession(
                        "hash",
                        UUID.randomUUID(),
                        "demo_test",
                        null,
                        IP,
                        null,
                        now,
                        now.plusSeconds(86_400));

        when(servletRequest.getRemoteAddr()).thenReturn(IP);
        when(sessions.resolve("token")).thenReturn(Optional.of(session));
        when(music.isEnabled()).thenReturn(true);
        when(music.perSessionLimit()).thenReturn(1);
        when(music.clipSeconds()).thenReturn(15);
        when(music.isGlobalBudgetAvailable()).thenReturn(true);
        when(credentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.of("platform-key"));
        when(limiter.tryAcquireMusic(IP)).thenReturn(true);
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
    }

    /** A real {@link ObjectProvider}: the composer is optional, exactly as in a lean install. */
    private static ObjectProvider<MusicComposer> providerOf(MusicComposer... composers) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < composers.length; i++) {
            beans.addBean("composer" + i, composers[i]);
        }
        return beans.getBeanProvider(MusicComposer.class);
    }

    private DemoMusicController controllerWithoutAComposer() {
        return new DemoMusicController(sessions, music, limiter, providerOf(), credentials, costs);
    }

    private ResponseEntity<DemoMusicResponse> post(String prompt) {
        return controller.generate("token", new DemoMusicRequest(prompt), servletRequest);
    }

    @Test
    void happyPathSpendsTheAllowanceThenComposesAndReturnsACapabilityUrl() {
        UUID id = UUID.randomUUID();
        when(client.composeMusic(
                        "warm acoustic guitar", 15_000, "music_v2_5", false, "platform-key"))
                .thenReturn(MP3);
        when(music.store(session, MP3, "warm acoustic guitar", "music_v2_5", 15, IP))
                .thenReturn(id);

        ResponseEntity<DemoMusicResponse> response = post("  warm acoustic guitar ");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        DemoMusicResponse body = response.getBody();
        assertEquals("/api/demo/music/" + id, body.musicUrl());
        assertFalse(body.simulated());
        assertEquals("music_v2_5", body.model());
        assertTrue(body.lowCostModel());
        assertEquals(1, body.musicUsed());
        assertEquals(15, body.durationSeconds());

        InOrder order = inOrder(limiter, music, client);
        order.verify(limiter).tryAcquireMusic(IP);
        order.verify(music).recordAttempt(session);
        order.verify(client)
                .composeMusic(anyString(), anyInt(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void unknownTokenIsTheOnlyNon200() {
        when(sessions.resolve("token")).thenReturn(Optional.empty());

        ResponseEntity<DemoMusicResponse> response = post("jazz");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody().simulated());
        verifyNoInteractions(client, limiter);
    }

    @Test
    void perIpLimitStopsTheRequestBeforeAnyAllowanceOrProviderSpend() {
        when(limiter.tryAcquireMusic(IP)).thenReturn(false);

        DemoMusicResponse body = post("jazz").getBody();

        assertTrue(body.simulated());
        assertNull(body.musicUrl());
        verify(music, never()).recordAttempt(any());
        verifyNoInteractions(client);
    }

    @Test
    void aRefusedPromptCostsNeitherARateLimitTokenNorAnAllowance() {
        when(music.refusalFor("bad prompt")).thenReturn("Let's keep it friendly.");

        DemoMusicResponse body = post("bad prompt").getBody();

        assertTrue(body.simulated());
        assertEquals("Let's keep it friendly.", body.message());
        verify(limiter, never()).tryAcquireMusic(anyString());
        verify(music, never()).recordAttempt(any());
        verifyNoInteractions(client);
    }

    @Test
    void killSwitchMissingPlatformKeyCapAndGlobalBudgetAllRefuseSoftlyWithoutSpending() {
        when(music.isEnabled()).thenReturn(false);
        assertTrue(post("jazz").getBody().simulated());

        when(music.isEnabled()).thenReturn(true);
        when(credentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.empty());
        assertTrue(post("jazz").getBody().simulated());

        when(credentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.of("platform-key"));
        when(music.isCapReached(session)).thenReturn(true);
        assertTrue(post("jazz").getBody().simulated());

        when(music.isCapReached(session)).thenReturn(false);
        when(music.isGlobalBudgetAvailable()).thenReturn(false);
        assertTrue(post("jazz").getBody().simulated());

        verify(limiter, never()).tryAcquireMusic(anyString());
        verify(music, never()).recordAttempt(any());
        verifyNoInteractions(client);
    }

    @Test
    void readOnlySandboxCannotGenerate() {
        when(sessions.isReadOnly(eq(session), any())).thenReturn(true);

        assertTrue(post("jazz").getBody().simulated());
        verifyNoInteractions(client);
    }

    @Test
    void providerFailureIsA200SampleNeverAnError() {
        when(client.composeMusic(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new ElevenLabsApiException(500, "upstream exploded: secret-detail"));

        ResponseEntity<DemoMusicResponse> response = post("jazz");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().simulated());
        assertNull(response.getBody().musicUrl());
        assertFalse(response.getBody().message().contains("secret-detail"));
        // The allowance was spent up-front, so the count reflects the attempt.
        assertEquals(1, response.getBody().musicUsed());
    }

    @Test
    void aRejectedModelFallsBackToTheNextCheapestListedModel() {
        when(client.composeMusic(
                        anyString(), anyInt(), eq("music_v2_5"), anyBoolean(), anyString()))
                .thenThrow(new ElevenLabsApiException(422, "unknown model"));
        when(client.composeMusic(anyString(), anyInt(), eq("music_v1"), anyBoolean(), anyString()))
                .thenReturn(MP3);
        when(music.store(any(), any(), anyString(), anyString(), anyInt(), anyString()))
                .thenReturn(UUID.randomUUID());

        assertEquals("music_v1", post("jazz").getBody().model());
    }

    @Test
    void statusOffersMusicOnlyWhenARequestCouldActuallySucceed() {
        assertTrue(controller.status("token").enabled());
        assertEquals(1, controller.status("token").musicLimit());
        assertEquals(15, controller.status("token").clipSeconds());

        when(credentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.empty());
        assertFalse(controller.status("token").enabled());

        when(credentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.of("platform-key"));
        when(music.isEnabled()).thenReturn(false);
        assertFalse(controller.status("token").enabled());

        when(music.isEnabled()).thenReturn(true);
        assertFalse(controller.status("no-such-token").enabled());
        verifyNoInteractions(client, limiter);
    }

    @Test
    void servingAnUnknownIdIs404() {
        when(music.find(any())).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller.serve(UUID.randomUUID()).getStatusCode());
    }

    @Test
    void withNoMusicComposerRegisteredGenerateIsASoftPausedSampleAndSpendsNothing() {
        // CLOUD_SPEECH off (personal install): ElevenLabsMusicComposer is not a bean.
        DemoMusicController lean = controllerWithoutAComposer();

        ResponseEntity<DemoMusicResponse> response =
                lean.generate("token", new DemoMusicRequest("jazz"), servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().simulated());
        assertNull(response.getBody().musicUrl());
        assertTrue(response.getBody().message().contains("paused"));
        verify(limiter, never()).tryAcquireMusic(anyString());
        verify(music, never()).recordAttempt(any());
        verifyNoInteractions(client);
    }

    @Test
    void withNoMusicComposerRegisteredStatusSaysNotEnabled() {
        DemoMusicController lean = controllerWithoutAComposer();

        assertFalse(lean.status("token").enabled());
        assertEquals(15, lean.status("token").clipSeconds());
    }
}
