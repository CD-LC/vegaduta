package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelMediaSender} for {@link ChannelType#INSTAGRAM} - structurally
 * identical to {@link MetaMessengerMediaSender} (Meta proxies Instagram
 * business messaging through the same linked Page's token, same as {@code
 * MetaGraphApiClient#sendInstagramMessage} does for text), kept as its own
 * bean rather than one generic sender for the same reason {@link
 * MetaInstagramChannelAdapter} is its own class: the two platforms' payload
 * shapes have already diverged before and are likely to again.
 */
@Component
public class MetaInstagramMediaSender implements ChannelMediaSender {

    private final MetaSocialCredentialService credentialService;
    private final MetaMediaGraphApiClient mediaClient;

    public MetaInstagramMediaSender(MetaSocialCredentialService credentialService, MetaMediaGraphApiClient mediaClient) {
        this.credentialService = credentialService;
        this.mediaClient = mediaClient;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.INSTAGRAM;
    }

    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption) {
        MetaSocialCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        mediaClient.sendPageAttachment("instagram", credential.pageAccessToken(), to, bytes, contentType, filename, caption);
    }
}
