package com.agenticai.core.omnichannel.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppProvider;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppSendPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The connection test routes to the channel's own probe; WhatsApp probes the provider the next send
 * would use.
 */
class ChannelHealthControllerTest {

    private static WhatsAppProvider provider(
            int precedence, String label, boolean active, String identity) {
        WhatsAppProvider p = mock(WhatsAppProvider.class);
        when(p.precedence()).thenReturn(precedence);
        when(p.label()).thenReturn(label);
        when(p.isActive()).thenReturn(active);
        when(p.probeIdentity()).thenReturn(identity);
        return p;
    }

    private static ChannelConnection connection(String key) {
        ChannelConnection c = mock(ChannelConnection.class);
        when(c.key()).thenReturn(key);
        return c;
    }

    @Test
    void whatsAppProbesTheFirstActiveProviderByPrecedence() {
        WhatsAppProvider twilio = provider(20, "Twilio", true, "Acme");
        WhatsAppProvider meta = provider(10, "Meta Cloud API", true, "+1 555 0100");
        ChannelHealthController controller =
                new ChannelHealthController(
                        new WhatsAppDispatchService(List.of(twilio, meta), mock(WhatsAppSendPolicy.class)), List.of());

        ChannelHealthController.ChannelHealthResponse r = controller.check("WhatsApp");

        assertTrue(r.ok());
        assertEquals("whatsapp", r.key());
        assertEquals("Meta Cloud API - +1 555 0100", r.identity());
        verify(twilio, never()).probeIdentity();
    }

    @Test
    void noActiveProviderIsNotConnected() {
        ChannelHealthController controller =
                new ChannelHealthController(
                        new WhatsAppDispatchService(
                                List.of(provider(10, "Meta Cloud API", false, null)),
                                mock(WhatsAppSendPolicy.class)),
                        List.of());

        ChannelHealthController.ChannelHealthResponse r = controller.check("whatsapp");

        assertFalse(r.ok());
        assertEquals("not connected", r.detail());
    }

    @Test
    void otherChannelsUseTheirOwnProbe() {
        ChannelConnection slack = connection("slack");
        when(slack.probe()).thenReturn("Acme workspace");
        ChannelConnection telegram = connection("telegram");
        when(telegram.probe()).thenThrow(new ChannelNotConnectedException());
        ChannelConnection discord = connection("discord");
        when(discord.probe()).thenThrow(new IllegalStateException("401 from Discord"));
        ChannelHealthController controller =
                new ChannelHealthController(
                        new WhatsAppDispatchService(List.of(), mock(WhatsAppSendPolicy.class)), List.of(slack, telegram, discord));

        assertEquals("Acme workspace", controller.check("slack").identity());
        assertEquals("not connected", controller.check("telegram").detail());
        ChannelHealthController.ChannelHealthResponse failed = controller.check("discord");
        assertFalse(failed.ok());
        assertEquals("401 from Discord", failed.detail());
        assertEquals(
                "not connected",
                controller.check("teams").detail(),
                "a channel with no probe bean");
    }

    @Test
    void mobileSdkAndUnknownKeysExplainThemselves() {
        ChannelHealthController controller =
                new ChannelHealthController(new WhatsAppDispatchService(List.of(), mock(WhatsAppSendPolicy.class)), List.of());

        assertTrue(
                controller
                        .check("mobile-sdk")
                        .detail()
                        .startsWith("Mobile SDK has no remote credential"));
        ChannelHealthController.ChannelHealthResponse unknown = controller.check("fax");
        assertFalse(unknown.ok());
        assertNull(unknown.identity());
        assertTrue(unknown.detail().startsWith("Unknown channel 'fax'"));
    }
}
