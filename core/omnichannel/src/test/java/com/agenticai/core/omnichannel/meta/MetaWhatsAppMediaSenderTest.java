package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Same manual-mock convention this module already uses (see {@code
 * EmailMediaSenderTest}'s javadoc). Covers the one piece of behavior that's
 * specific to this sender rather than {@link MetaMediaGraphApiClient} itself
 * (that class's own request-building is covered by {@code
 * MetaMediaGraphApiClientTest}): {@link MetaWhatsAppMediaSender} mirrors
 * {@code WhatsAppDispatchService}'s "check Meta first" provider order for
 * text, but - unlike that class - does NOT fall through to Twilio for
 * media, since that path cannot actually deliver an attachment
 * (see the sender's own javadoc). A tenant on Twilio should see an
 * honest, actionable error instead of a wrong-provider attempt or a silent
 * no-op.
 */
class MetaWhatsAppMediaSenderTest {

    private MetaWhatsAppCredentialService credentialService;
    private MetaMediaGraphApiClient mediaClient;
    private MetaWhatsAppMediaSender sender;

    @BeforeEach
    void setUp() {
        credentialService = mock(MetaWhatsAppCredentialService.class);
        mediaClient = mock(MetaMediaGraphApiClient.class);
        sender = new MetaWhatsAppMediaSender(credentialService, mediaClient);
    }

    @Test
    void channelIsWhatsApp() {
        assertEquals(ChannelType.WHATSAPP, sender.channel());
    }

    @Test
    void sendThrowsAnActionableConfigurationExceptionWhenMetaIsNotTheTenantsConnectedProvider() {
        when(credentialService.isConfigured()).thenReturn(false);

        ChannelConfigurationException ex = assertThrows(ChannelConfigurationException.class, () ->
            sender.send("15551234567", "b".getBytes(), "image/png", "p.png", "caption")
        );
        assertTrue(ex.getMessage().toLowerCase().contains("meta"), ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("twilio") || ex.getMessage().toLowerCase().contains("qr"), ex.getMessage());
    }

    @Test
    void sendDelegatesToTheGraphApiClientWithTheResolvedCredentialWhenMetaIsConfigured() throws Exception {
        when(credentialService.isConfigured()).thenReturn(true);
        MetaWhatsAppCredentialService.DecryptedCredential credential =
            new MetaWhatsAppCredentialService.DecryptedCredential("PHONE123", "token-abc", "app-secret", "WABA1");
        when(credentialService.resolveActive()).thenReturn(credential);

        sender.send("15551234567", "bytes".getBytes(), "image/png", "p.png", "caption");

        verify(mediaClient).sendWhatsAppMedia(
            eq("PHONE123"), eq("token-abc"), eq("15551234567"), any(byte[].class), eq("image/png"), eq("p.png"), eq("caption")
        );
    }
}
