package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelMediaSender} for {@link ChannelType#WHATSAPP} - Meta Cloud
 * API only. Follows the same "check Meta first" provider preference {@code
 * WhatsAppDispatchService} uses for text sends (see that class's javadoc for
 * the full Meta &gt; Twilio order), but unlike the text-send path
 * this one does NOT fall through to Twilio if Meta isn't the tenant's
 * connected provider:
 * <ul>
 *   <li>Twilio's WhatsApp media send needs a publicly reachable {@code
 *   MediaUrl} - this platform has nowhere to host one for an arbitrary
 *   generated attachment, so that path is genuinely not implementable here
 *   yet, not just skipped.</li>
 * </ul>
 * A tenant whose only connected WhatsApp channel is Twilio gets an
 * honest, actionable "not supported for your connection" error instead of a
 * silent no-op or a wrong-provider attempt - same "never overclaim a
 * capability that isn't actually wired up" norm {@link ChannelMediaSender}'s
 * javadoc documents.
 */
@Component
public class MetaWhatsAppMediaSender implements ChannelMediaSender {

    private final MetaWhatsAppCredentialService credentialService;
    private final MetaMediaGraphApiClient mediaClient;

    public MetaWhatsAppMediaSender(MetaWhatsAppCredentialService credentialService, MetaMediaGraphApiClient mediaClient) {
        this.credentialService = credentialService;
        this.mediaClient = mediaClient;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.WHATSAPP;
    }

    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption) {
        if (!credentialService.isConfigured()) {
            throw new ChannelConfigurationException(
                "whatsapp",
                "Attachment forwarding needs a direct Meta WhatsApp Cloud API connection - Twilio "
                    + "WhatsApp doesn't support sending attachments yet. Connect Meta Business Platform first."
            );
        }
        MetaWhatsAppCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        mediaClient.sendWhatsAppMedia(credential.phoneNumberId(), credential.accessToken(), to, bytes, contentType, filename, caption);
    }
}
