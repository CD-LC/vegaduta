package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/** Messenger's connection state for the Channels screen: the Facebook Page name. */
@Component
public class MetaMessengerChannelConnection implements ChannelConnection {

    private final MetaSocialCredentialService credentialService;
    private final MetaGraphApiClient apiClient;

    public MetaMessengerChannelConnection(
            MetaSocialCredentialService credentialService, MetaGraphApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String key() {
        return "messenger";
    }

    @Override
    public Summary summary() {
        return credentialService
                .current()
                .filter(c -> c.status() == MetaSocialCredentialStatus.ACTIVE)
                .map(c -> Summary.connected(c.pageName() != null ? c.pageName() : "connected"))
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            MetaSocialCredentialService.DecryptedCredential c = credentialService.resolveActive();
            MetaGraphApiClient.PageInfo info =
                    apiClient.fetchPageInfo(c.pageId(), c.pageAccessToken());
            return info.name() != null ? info.name() : c.pageId();
        } catch (MetaSocialNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
