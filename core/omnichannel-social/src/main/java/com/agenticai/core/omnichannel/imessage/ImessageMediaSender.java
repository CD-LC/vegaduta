package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelMediaSender} for iMessage: uploads the bytes to the tenant's BlueBubbles server,
 * which sends them as an iMessage attachment. Rate limited with the text sends. Like the text path
 * it resolves the tenant's own server from storage - a media forward has no inbound request to
 * borrow credentials from.
 */
@Component
public class ImessageMediaSender implements ChannelMediaSender {

    private final ImessageCredentialService credentialService;
    private final ImessageApiClient apiClient;
    private final ImessageRateLimiter rateLimiter;

    public ImessageMediaSender(
            ImessageCredentialService credentialService,
            ImessageApiClient apiClient,
            ImessageRateLimiter rateLimiter) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.IMESSAGE;
    }

    /** {@code to} is a chat GUID or a bare phone number / email, as for {@link ImessageChannelAdapter.SendPayload}. */
    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption) {
        if (!rateLimiter.tryOutbound(ImessageChannelAdapter.tenantKey())) {
            throw new ChannelDeliveryException("imessage", "iMessage send rate limit reached - try again shortly");
        }
        ImessageCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        apiClient.sendAttachment(
                credential.serverUrl(), credential.password(), to, bytes, contentType, filename, caption);
    }
}
