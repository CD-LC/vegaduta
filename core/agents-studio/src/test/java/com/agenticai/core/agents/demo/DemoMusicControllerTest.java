package com.agenticai.core.agents.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.demo.DemoMusicController.DemoMusicRequest;
import com.agenticai.core.agents.demo.DemoMusicController.DemoMusicResponse;
import com.agenticai.core.agentspi.MusicComposer;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.modelcost.MediaModality;
import com.agenticai.core.providers.modelcost.MediaModelCostService;
import com.agenticai.core.providers.modelcost.MediaModelCostService.ModelChoice;
import com.agenticai.core.tenancy.demo.DemoGeneratedMusic;
import com.agenticai.core.tenancy.demo.DemoMusicService;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRateLimiter;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** The sandbox music endpoint: every refusal is a 200 sample, in the documented order. */
class DemoMusicControllerTest {

    private DemoSessionService sessions;
    private DemoMusicService music;
    private DemoSessionRateLimiter limiter;
    private MusicComposer composer;
    private PlatformToolCredentialService credentials;
    private MediaModelCostService costs;
    private ObjectProvider<MusicComposer> composerProvider;
    private DemoMusicController controller;
    private DemoSession session;
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        sessions = mock(DemoSessionService.class);
        music = mock(DemoMusicService.class);
        limiter = mock(DemoSessionRateLimiter.class);
        composer = mock(MusicComposer.class);
        credentials = mock(PlatformToolCredentialService.class);
        costs = mock(MediaModelCostService.class);
        composerProvider = mock(ObjectProvider.class);
        when(composerProvider.getIfAvailable()).thenReturn(composer);
        controller =
                new DemoMusicController(
                        sessions, music, limiter, composerProvider, credentials, costs);

        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getMusicCount()).thenReturn(0);
        when(sessions.resolve("tok")).thenReturn(Optional.of(session));
        when(music.perSessionLimit()).thenReturn(1);
        when(music.clipSeconds()).thenReturn(15);
        when(music.isEnabled()).thenReturn(true);
        when(music.isGlobalBudgetAvailable()).thenReturn(true);
        when(credentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.of("key"));
        when(limiter.tryAcquireMusic(anyString())).thenReturn(true);
        http.setRemoteAddr("198.51.100.4");
    }

    private static ModelChoice model(String id) {
        return new ModelChoice(MediaModality.MUSIC, "ELEVENLABS", id, BigDecimal.ONE, 1);
    }

    private ResponseEntity<DemoMusicResponse> generate(String prompt) {
        return controller.generate("tok", new DemoMusicRequest(prompt), http);
    }

    private static void assertSample(ResponseEntity<DemoMusicResponse> r, String messagePart) {
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().simulated()).isTrue();
        assertThat(r.getBody().musicUrl()).isNull();
        assertThat(r.getBody().message()).contains(messagePart);
    }

    @Test
    void anUnknownTokenIsTheOnly404() {
        ResponseEntity<DemoMusicResponse> r =
                controller.generate("nope", new DemoMusicRequest("x"), http);

        assertThat(r.getStatusCode().value()).isEqualTo(404);
        assertThat(r.getBody().simulated()).isTrue();
        assertThat(r.getBody().musicLimit()).isEqualTo(1);
    }

    @Test
    void aMissingComposerKillSwitchOrPlatformKeyAllMeanPaused() {
        when(composerProvider.getIfAvailable()).thenReturn(null);
        assertSample(generate("a tune"), "paused");

        when(composerProvider.getIfAvailable()).thenReturn(composer);
        when(music.isEnabled()).thenReturn(false);
        assertSample(generate("a tune"), "paused");

        when(music.isEnabled()).thenReturn(true);
        when(credentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.empty());
        assertSample(generate("a tune"), "paused");
    }

    @Test
    void anEndedOrViewOnlySandboxGetsASample() {
        when(session.isExpired(any(Instant.class))).thenReturn(true);
        assertSample(generate("a tune"), "has ended");

        when(session.isExpired(any(Instant.class))).thenReturn(false);
        when(sessions.isReadOnly(eq(session), any(Instant.class))).thenReturn(true);
        assertSample(generate("a tune"), "view-only");
    }

    @Test
    void theAllowanceTheDailyBudgetAndTheScreenAreCheckedBeforeTheRateLimiter() {
        when(music.isCapReached(session)).thenReturn(true);
        assertSample(generate("a tune"), "used your sandbox music clip");

        when(music.isCapReached(session)).thenReturn(false);
        when(music.isGlobalBudgetAvailable()).thenReturn(false);
        assertSample(generate("a tune"), "budget is used up");

        when(music.isGlobalBudgetAvailable()).thenReturn(true);
        when(music.refusalFor("rude")).thenReturn("That prompt isn't allowed.");
        assertSample(generate("rude"), "isn't allowed");

        verify(limiter, never()).tryAcquireMusic(anyString());
        verify(music, never()).recordAttempt(any());
    }

    @Test
    void aRateLimitedDeviceGetsASampleAndSpendsNothing() {
        when(limiter.tryAcquireMusic("198.51.100.4")).thenReturn(false);

        assertSample(generate("a tune"), "from this device recently");
        verify(music, never()).recordAttempt(any());
    }

    @Test
    void aSuccessfulClipIsStoredAfterTheAllowanceIsSpentAndNamesTheModel() {
        UUID clipId = UUID.randomUUID();
        byte[] audio = {1, 2, 3};
        when(costs.cheapestFirst(MediaModality.MUSIC, "ELEVENLABS"))
                .thenReturn(List.of(model("m-cheap")));
        when(composer.compose("  calm piano ".trim(), 15000, "m-cheap", false, "key"))
                .thenReturn(audio);
        when(music.store(session, audio, "calm piano", "m-cheap", 15, "198.51.100.4"))
                .thenReturn(clipId);

        ResponseEntity<DemoMusicResponse> r = generate("  calm piano ");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        DemoMusicResponse body = r.getBody();
        assertThat(body.simulated()).isFalse();
        assertThat(body.musicUrl()).isEqualTo("/api/demo/music/" + clipId);
        assertThat(body.musicUsed()).isEqualTo(1);
        assertThat(body.model()).isEqualTo("m-cheap");
        assertThat(body.lowCostModel()).isTrue();
        assertThat(body.durationSeconds()).isEqualTo(15);
        verify(music).recordAttempt(session);
    }

    @Test
    void aNullPromptIsTreatedAsEmpty() {
        when(costs.cheapestFirst(any(), anyString())).thenReturn(List.of(model("m")));
        when(composer.compose(eq(""), anyInt(), anyString(), anyBoolean(), anyString()))
                .thenReturn(new byte[] {1});
        when(music.store(any(), any(), eq(""), anyString(), anyInt(), anyString()))
                .thenReturn(UUID.randomUUID());

        assertThat(generate(null).getBody().simulated()).isFalse();
    }

    @Test
    void aRequestShapedRejectionMovesToTheNextModel() {
        UUID clipId = UUID.randomUUID();
        when(costs.cheapestFirst(MediaModality.MUSIC, "ELEVENLABS"))
                .thenReturn(List.of(model("m-a"), model("m-b")));
        when(composer.compose(anyString(), anyInt(), eq("m-a"), anyBoolean(), anyString()))
                .thenThrow(new MusicComposer.ComposeFailedException(422, "bad model", null));
        when(composer.compose(anyString(), anyInt(), eq("m-b"), anyBoolean(), anyString()))
                .thenReturn(new byte[] {9});
        when(music.store(any(), any(), anyString(), eq("m-b"), anyInt(), anyString()))
                .thenReturn(clipId);

        assertThat(generate("tune").getBody().model()).isEqualTo("m-b");
    }

    @Test
    void anUpstreamServerErrorStopsAtOnceAndServesABusySample() {
        when(costs.cheapestFirst(any(), anyString()))
                .thenReturn(List.of(model("m-a"), model("m-b")));
        when(composer.compose(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new MusicComposer.ComposeFailedException(503, "down", null));

        ResponseEntity<DemoMusicResponse> r = generate("tune");

        assertSample(r, "busy right now");
        assertThat(r.getBody().musicUsed()).isEqualTo(1);
        verify(composer).compose(anyString(), anyInt(), eq("m-a"), anyBoolean(), anyString());
        verify(composer, never())
                .compose(anyString(), anyInt(), eq("m-b"), anyBoolean(), anyString());
    }

    @Test
    void whenEveryModelIsRejectedOrNoneIsListedTheClipFailsGracefully() {
        when(costs.cheapestFirst(any(), anyString())).thenReturn(List.of(model("m-a")));
        when(composer.compose(anyString(), anyInt(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new MusicComposer.ComposeFailedException(404, "gone", null));
        assertSample(generate("tune"), "busy right now");

        when(costs.cheapestFirst(any(), anyString())).thenReturn(List.of());
        assertSample(generate("tune"), "busy right now");
    }

    @Test
    void statusIsEnabledOnlyWhenKillSwitchComposerKeyAndAllowanceAllHold() {
        when(session.getMusicCount()).thenReturn(1);
        DemoMusicController.DemoMusicStatus on = controller.status("tok");
        assertThat(on.enabled()).isTrue();
        assertThat(on.musicUsed()).isEqualTo(1);
        assertThat(on.clipSeconds()).isEqualTo(15);

        when(music.perSessionLimit()).thenReturn(0);
        assertThat(controller.status("tok").enabled()).isFalse();
        when(music.perSessionLimit()).thenReturn(1);
        when(composerProvider.getIfAvailable()).thenReturn(null);
        assertThat(controller.status("tok").enabled()).isFalse();
        when(composerProvider.getIfAvailable()).thenReturn(composer);
        when(music.isEnabled()).thenReturn(false);
        assertThat(controller.status("tok").enabled()).isFalse();
        when(music.isEnabled()).thenReturn(true);
        when(credentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS))
                .thenReturn(Optional.empty());
        assertThat(controller.status("tok").enabled()).isFalse();
    }

    @Test
    void statusForAnUnknownTokenIsSimplyDisabled() {
        DemoMusicController.DemoMusicStatus s = controller.status("nope");
        assertThat(s.enabled()).isFalse();
        assertThat(s.musicUsed()).isZero();
    }

    @Test
    void aStoredClipIsServedWithHardenedImmutableHeaders() {
        UUID id = UUID.randomUUID();
        DemoGeneratedMusic stored = mock(DemoGeneratedMusic.class);
        when(stored.getContentType()).thenReturn("audio/mpeg");
        when(music.find(id)).thenReturn(Optional.of(stored));
        when(music.readBytes(stored)).thenReturn(Optional.of(new byte[] {7, 7}));

        ResponseEntity<byte[]> r = controller.serve(id);

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsExactly(7, 7);
        assertThat(r.getHeaders().getContentType().toString()).isEqualTo("audio/mpeg");
        assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeaders().getCacheControl()).contains("private").contains("immutable");
    }

    @Test
    void anUnknownClipOrMissingBytesIs404() {
        UUID id = UUID.randomUUID();
        assertThat(controller.serve(id).getStatusCode().value()).isEqualTo(404);

        DemoGeneratedMusic stored = mock(DemoGeneratedMusic.class);
        when(music.find(id)).thenReturn(Optional.of(stored));
        when(music.readBytes(stored)).thenReturn(Optional.empty());
        assertThat(controller.serve(id).getStatusCode().value()).isEqualTo(404);
    }
}
