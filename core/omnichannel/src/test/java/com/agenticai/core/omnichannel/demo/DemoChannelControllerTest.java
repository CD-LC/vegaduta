package com.agenticai.core.omnichannel.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.demo.DemoChannelController.ChannelsStatus;
import com.agenticai.core.tenancy.demo.DemoChannelLink;
import com.agenticai.core.tenancy.demo.DemoChannelLinkService;
import com.agenticai.core.tenancy.demo.DemoChannelLinkState;
import com.agenticai.core.tenancy.demo.DemoChannelMessage;
import com.agenticai.core.tenancy.demo.DemoChannelRateLimiter;
import com.agenticai.core.tenancy.demo.DemoChannelType;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Status shaping and rate-limited actions of the sandbox Telegram/WhatsApp pairing endpoints. */
class DemoChannelControllerTest {

    private DemoSessionService sessions;
    private DemoChannelLinkService links;
    private DemoChannelRateLimiter limiter;
    private DemoTelegramBot telegram;
    private DemoWhatsAppBot whatsApp;
    private DemoChannelController controller;
    private DemoSession session;

    @BeforeEach
    void setUp() {
        sessions = mock(DemoSessionService.class);
        links = mock(DemoChannelLinkService.class);
        limiter = mock(DemoChannelRateLimiter.class);
        telegram = mock(DemoTelegramBot.class);
        whatsApp = mock(DemoWhatsAppBot.class);
        controller = new DemoChannelController(sessions, links, limiter, telegram, whatsApp);
        session = mock(DemoSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(sessions.resolve("tok")).thenReturn(Optional.of(session));
        when(limiter.tryAcquire(anyString())).thenReturn(true);
        when(telegram.isAvailable()).thenReturn(true);
        when(telegram.botUsername()).thenReturn("vegaduta_bot");
        when(links.linkFor(any(), any())).thenReturn(Optional.empty());
    }

    private static DemoChannelLink link(
            DemoChannelLinkState state, boolean linked, String code, int messages) {
        DemoChannelLink l = mock(DemoChannelLink.class);
        when(l.getState()).thenReturn(state);
        when(l.isLinked()).thenReturn(linked);
        when(l.getPairingCode()).thenReturn(code);
        when(l.getMessageCount()).thenReturn(messages);
        when(l.getPeerLabel()).thenReturn("@asha");
        when(l.getLinkedAt()).thenReturn(Instant.parse("2026-10-01T10:00:00Z"));
        return l;
    }

    private ChannelsStatus body(ResponseEntity<?> response) {
        return (ChannelsStatus) response.getBody();
    }

    @Test
    void anUnknownTokenIsA404OnEveryEndpoint() {
        assertThat(controller.status("nope", null).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.pairTelegram("nope").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.unlinkTelegram("nope").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.pairWhatsApp("nope").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.unlinkWhatsApp("nope").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.recordWhatsAppPreview("nope").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void withNoBotsAndNoLinksTheStatusIsUnavailableAndPreview() {
        when(telegram.isAvailable()).thenReturn(false);
        when(whatsApp.isAvailable()).thenReturn(false);

        ChannelsStatus status = body(controller.status("tok", null));

        assertThat(status.telegram().available()).isFalse();
        assertThat(status.whatsapp().mode()).isEqualTo("PREVIEW");
        assertThat(status.whatsapp().used()).isFalse();
    }

    @Test
    void anAvailableBotWithoutALinkOffersPairing() {
        ChannelsStatus status = body(controller.status("tok", null));

        assertThat(status.telegram().available()).isTrue();
        assertThat(status.telegram().state()).isEqualTo("NONE");
        assertThat(status.telegram().botUsername()).isEqualTo("vegaduta_bot");
    }

    @Test
    void aLinkedTelegramChatShowsMessagesSinceTheGivenInstant() {
        DemoChannelLink linked = link(DemoChannelLinkState.LINKED, true, null, 2);
        when(links.linkFor(session, DemoChannelType.TELEGRAM)).thenReturn(Optional.of(linked));
        DemoChannelMessage message = mock(DemoChannelMessage.class);
        when(message.getDirection()).thenReturn(DemoChannelMessage.Direction.values()[0]);
        when(message.getBody()).thenReturn("hello");
        when(message.getCreatedAt()).thenReturn(Instant.parse("2026-10-01T10:05:00Z"));
        Instant since = Instant.parse("2026-10-01T10:00:00Z");
        when(links.messagesSince(session, DemoChannelType.TELEGRAM, since))
                .thenReturn(List.of(message));

        ChannelsStatus status = body(controller.status("tok", " 2026-10-01T10:00:00Z "));

        assertThat(status.telegram().state()).isEqualTo("LINKED");
        assertThat(status.telegram().peerLabel()).isEqualTo("@asha");
        assertThat(status.telegram().messages()).extracting(m -> m.text()).containsExactly("hello");
    }

    @Test
    void aPendingLinkShowsItsPairingCodeAndNoMessages() {
        DemoChannelLink pending = link(DemoChannelLinkState.PENDING, false, "ABC123", 0);
        when(links.linkFor(session, DemoChannelType.TELEGRAM)).thenReturn(Optional.of(pending));
        when(telegram.deepLinkFor("ABC123")).thenReturn("https://t.me/vegaduta_bot?start=ABC123");

        ChannelsStatus status = body(controller.status("tok", "garbage"));

        assertThat(status.telegram().pairingCode()).isEqualTo("ABC123");
        assertThat(status.telegram().deepLink()).contains("ABC123");
        assertThat(status.telegram().messages()).isEmpty();
        verify(links, never()).messagesSince(any(), any(), any());
    }

    @Test
    void anOverlongOrBlankSinceIsIgnored() {
        DemoChannelLink linked = link(DemoChannelLinkState.LINKED, true, null, 0);
        when(links.linkFor(session, DemoChannelType.TELEGRAM)).thenReturn(Optional.of(linked));
        when(links.messagesSince(any(), any(), any())).thenReturn(List.of());

        controller.status("tok", "x".repeat(41));
        controller.status("tok", " ");

        verify(links, org.mockito.Mockito.times(2))
                .messagesSince(session, DemoChannelType.TELEGRAM, null);
    }

    @Test
    void aLiveWhatsAppBotReportsItsLinkAndBusinessNumber() {
        when(whatsApp.isAvailable()).thenReturn(true);
        when(whatsApp.businessNumberMasked()).thenReturn("+91 ****5678");
        when(whatsApp.deepLinkFor("WA1234")).thenReturn("https://wa.me/919800005678?text=WA1234");
        DemoChannelLink pending = link(DemoChannelLinkState.PENDING, false, "WA1234", 0);
        when(links.linkFor(session, DemoChannelType.WHATSAPP)).thenReturn(Optional.of(pending));

        var wa = body(controller.status("tok", null)).whatsapp();

        assertThat(wa.mode()).isEqualTo("LIVE");
        assertThat(wa.available()).isTrue();
        assertThat(wa.pairingCode()).isEqualTo("WA1234");
        assertThat(wa.businessNumberMasked()).isEqualTo("+91 ****5678");
    }

    @Test
    void aLiveWhatsAppBotWithoutALinkIsNone() {
        when(whatsApp.isAvailable()).thenReturn(true);

        var wa = body(controller.status("tok", null)).whatsapp();

        assertThat(wa.state()).isEqualTo("NONE");
        assertThat(wa.used()).isFalse();
    }

    @Test
    void thePreviewModeReportsUseOfThePreview() {
        DemoChannelLink used = link(DemoChannelLinkState.LINKED, true, null, 3);
        when(links.linkFor(session, DemoChannelType.WHATSAPP_PREVIEW))
                .thenReturn(Optional.of(used));

        var wa = body(controller.status("tok", null)).whatsapp();

        assertThat(wa.used()).isTrue();
        assertThat(wa.messageCount()).isEqualTo(3);
    }

    @Test
    void aStatusFailureDegradesToNoChannels() {
        when(telegram.isAvailable()).thenThrow(new IllegalStateException("db down"));

        ChannelsStatus status = body(controller.status("tok", null));

        assertThat(status.telegram().available()).isFalse();
        assertThat(status.whatsapp().mode()).isEqualTo("PREVIEW");
    }

    @Test
    void telegramPairingIssuesACodeUnlessExpiredUnavailableRateLimitedOrFailing() {
        controller.pairTelegram("tok");
        verify(links).issuePairingCode(session, DemoChannelType.TELEGRAM);

        when(session.isExpired(any(Instant.class))).thenReturn(true);
        assertThat(body(controller.pairTelegram("tok")).telegram().available()).isFalse();
        when(session.isExpired(any(Instant.class))).thenReturn(false);

        when(telegram.isAvailable()).thenReturn(false);
        assertThat(body(controller.pairTelegram("tok")).telegram().available()).isFalse();
        when(telegram.isAvailable()).thenReturn(true);

        when(limiter.tryAcquire("pair:" + session.getId())).thenReturn(false);
        controller.pairTelegram("tok");
        verify(links, org.mockito.Mockito.times(1))
                .issuePairingCode(any(), eq(DemoChannelType.TELEGRAM));
        when(limiter.tryAcquire(anyString())).thenReturn(true);

        doThrow(new IllegalStateException("boom")).when(links).issuePairingCode(any(), any());
        assertThat(body(controller.pairTelegram("tok")).telegram().available()).isFalse();
    }

    @Test
    void whatsAppPairingNeedsALiveBotAnActiveSandboxAndTheRateLimit() {
        controller.pairWhatsApp("tok");
        verify(links, never()).issuePairingCode(any(), eq(DemoChannelType.WHATSAPP));

        when(whatsApp.isAvailable()).thenReturn(true);
        controller.pairWhatsApp("tok");
        verify(links).issuePairingCode(session, DemoChannelType.WHATSAPP);

        when(limiter.tryAcquire("wa-issue:" + session.getId())).thenReturn(false);
        controller.pairWhatsApp("tok");
        verify(links, org.mockito.Mockito.times(1))
                .issuePairingCode(any(), eq(DemoChannelType.WHATSAPP));
        when(limiter.tryAcquire(anyString())).thenReturn(true);

        when(session.isExpired(any(Instant.class))).thenReturn(true);
        controller.pairWhatsApp("tok");
        when(session.isExpired(any(Instant.class))).thenReturn(false);

        doThrow(new IllegalStateException("boom")).when(links).issuePairingCode(any(), any());
        assertThat(controller.pairWhatsApp("tok").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void unlinkingRevokesTheLinkAndSurvivesFailures() {
        controller.unlinkTelegram("tok");
        controller.unlinkWhatsApp("tok");
        verify(links).revoke(session, DemoChannelType.TELEGRAM);
        verify(links).revoke(session, DemoChannelType.WHATSAPP);

        doThrow(new IllegalStateException("boom")).when(links).revoke(any(), any());
        assertThat(controller.unlinkTelegram("tok").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.unlinkWhatsApp("tok").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void aWhatsAppPreviewUseIsRecordedOncePerRateLimitWindow() {
        controller.recordWhatsAppPreview("tok");
        verify(links).recordPreviewUse(session, DemoChannelType.WHATSAPP_PREVIEW);

        when(limiter.tryAcquire("wa-preview:" + session.getId())).thenReturn(false);
        controller.recordWhatsAppPreview("tok");
        verify(links, org.mockito.Mockito.times(1)).recordPreviewUse(any(), any());
        when(limiter.tryAcquire(anyString())).thenReturn(true);

        doThrow(new IllegalStateException("boom")).when(links).recordPreviewUse(any(), any());
        assertThat(controller.recordWhatsAppPreview("tok").getStatusCode().value()).isEqualTo(200);
    }
}
