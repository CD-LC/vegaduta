package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import java.net.URI;
import org.springframework.stereotype.Component;

/** iMessage's connection state for the Channels screen: the BlueBubbles server host, never a credential. */
@Component
public class ImessageChannelConnection implements ChannelConnection {

    private final ImessageCredentialService credentialService;
    private final ImessageApiClient apiClient;

    public ImessageChannelConnection(
            ImessageCredentialService credentialService, ImessageApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String key() {
        return "imessage";
    }

    @Override
    public Summary summary() {
        // activeServerUrl() decrypts nothing: a key mismatch must not take the whole status list down.
        return credentialService
                .activeServerUrl()
                .map(url -> Summary.connected(hostOf(url)))
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            ImessageCredentialService.DecryptedCredential c = credentialService.resolveActive();
            ImessageApiClient.ServerInfo info = apiClient.fetchServerInfo(c.serverUrl(), c.password());
            String version = info.serverVersion() == null ? "" : " v" + info.serverVersion();
            return "BlueBubbles" + version + (info.helperConnected() ? "" : " (Private API helper offline)");
        } catch (ImessageNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }

    private static String hostOf(String serverUrl) {
        try {
            String host = URI.create(serverUrl).getHost();
            return host == null ? "connected" : host;
        } catch (RuntimeException e) {
            return "connected";
        }
    }
}
