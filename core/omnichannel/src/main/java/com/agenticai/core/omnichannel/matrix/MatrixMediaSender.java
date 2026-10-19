package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.omnichannel.media.ChannelMediaSender;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelMediaSender} for {@link ChannelType#MATRIX}: uploads the file to the homeserver's
 * media repository and posts it to the room as an m.file / m.image / m.audio / m.video event.
 * {@code to} is the room id. Files are posted unencrypted (this channel has no E2EE).
 */
@Component
public class MatrixMediaSender implements ChannelMediaSender {

    private final MatrixCredentialService credentialService;
    private final MatrixApiClient apiClient;

    public MatrixMediaSender(MatrixCredentialService credentialService, MatrixApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.MATRIX;
    }

    @Override
    public void send(String to, byte[] bytes, String contentType, String filename, String caption) {
        MatrixCredentialService.ActiveConnection connection = credentialService.resolveActive();
        apiClient.sendFile(
                connection.endpoint(),
                to,
                "vd-file-" + UUID.randomUUID(),
                filename,
                contentType,
                bytes,
                caption,
                null,
                null);
    }
}
