package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelMediaSender} for {@link ChannelType#MESSENGER}. Messenger
 * has only ever had the one connection path (a Facebook Page's access token
 * via {@link MetaSocialCredentialService} - no Twilio/QR-style alternative
 * provider exists for this channel), so unlike {@link MetaWhatsAppMediaSender}
 * there's no provider-selection ambiguity to resolve here: {@code
 * credentialService.resolveActive()} throws its own actionable {@link
 * MetaSocialNotConfiguredException} if nothing is connected yet, which is
 * exactly what {@link com.agenticai.core.omnichannel.media.ChannelMediaSendPortImpl}
 * needs to report back to the caller.
 */
@Component
public class MetaMessengerMediaSender implements ChannelMediaSender {

    private final MetaSocialCredentialService credentialService;
    private final MetaMediaGraphApiClient mediaClient;

    public MetaMessengerMediaSender(MetaSocialCredentialService credentialService, MetaMediaGraphApiClient mediaClient) {
        this.credentialService = credentialService;
        this.mediaClient = mediaClient;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.MESSENGER;
    }

    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption) {
        MetaSocialCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        mediaClient.sendPageAttachment("messenger", credential.pageAccessToken(), to, bytes, contentType, filename, caption);
    }
}
