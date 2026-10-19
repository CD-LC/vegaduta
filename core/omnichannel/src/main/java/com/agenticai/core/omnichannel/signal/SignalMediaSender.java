package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelMediaSender} for {@link ChannelType#SIGNAL}: a file the agent forwards to a
 * person or group, with the caption as the message text. Like the other media senders this is the
 * agent's own tool call, not an auto-reply, so it goes straight to signal-cli.
 */
@Component
public class SignalMediaSender implements ChannelMediaSender {

    private final SignalCredentialService credentialService;
    private final SignalApiClient apiClient;
    private final int maxBytes;

    @Autowired
    public SignalMediaSender(
            SignalCredentialService credentialService,
            SignalApiClient apiClient,
            @Value("${app.signal.max-attachment-bytes:20971520}") int maxBytes) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.maxBytes = maxBytes;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.SIGNAL;
    }

    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption) {
        if (bytes == null || bytes.length == 0) {
            throw new ChannelConfigurationException("signal", "There is nothing to send: the attachment is empty.");
        }
        if (bytes.length > maxBytes) {
            throw new ChannelConfigurationException(
                "signal", "The attachment is larger than the " + (maxBytes / (1024 * 1024)) + " MB limit.");
        }
        String recipient = SignalAddress.toRecipient(to);
        SignalConnection connection = credentialService.resolveActive();
        apiClient.send(
            connection,
            recipient,
            caption == null ? "" : caption,
            List.of(new SignalApiClient.Attachment(bytes, contentType, filename)));
    }
}
