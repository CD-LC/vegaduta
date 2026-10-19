package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/** Matrix's connection state for the Channels screen: the bot's MXID. */
@Component
public class MatrixChannelConnection implements ChannelConnection {

    private final MatrixCredentialService credentialService;
    private final MatrixApiClient apiClient;

    public MatrixChannelConnection(MatrixCredentialService credentialService, MatrixApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String key() {
        return "matrix";
    }

    @Override
    public Summary summary() {
        return credentialService
                .current()
                .map(
                        c ->
                                switch (c.status()) {
                                    case ACTIVE -> Summary.connected(c.botUserId());
                                    case INVALID -> Summary.needsAttention("token rejected - reconnect");
                                    case DISCONNECTED -> Summary.notConnected("+ connect");
                                })
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            MatrixCredentialService.ActiveConnection c = credentialService.resolveActive();
            return apiClient.whoAmI(c.endpoint()).userId();
        } catch (MatrixNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
