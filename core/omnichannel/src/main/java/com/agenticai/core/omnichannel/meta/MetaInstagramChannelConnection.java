package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.meta.dto.MetaSocialCredentialResponse;
import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/**
 * Instagram DM's connection state for the Channels screen: the linked Business Account's
 * {@code @username}.
 */
@Component
public class MetaInstagramChannelConnection implements ChannelConnection {

    private final MetaSocialCredentialService credentialService;
    private final MetaGraphApiClient apiClient;

    public MetaInstagramChannelConnection(
            MetaSocialCredentialService credentialService, MetaGraphApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String key() {
        return "instagram";
    }

    @Override
    public Summary summary() {
        MetaSocialCredentialResponse social =
                credentialService
                        .current()
                        .filter(c -> c.status() == MetaSocialCredentialStatus.ACTIVE)
                        .orElse(null);
        if (social != null && social.instagramConnected()) {
            return Summary.connected(
                    social.instagramUsername() != null
                            ? "@" + social.instagramUsername()
                            : "connected");
        }
        return Summary.notConnected("+ connect");
    }

    @Override
    public String probe() {
        try {
            MetaSocialCredentialService.DecryptedCredential c = credentialService.resolveActive();
            if (!c.hasInstagram()) {
                throw new IllegalArgumentException(
                        "No Instagram Business Account linked to this Page credential");
            }
            String username =
                    apiClient.fetchInstagramUsername(
                            c.instagramBusinessAccountId(), c.pageAccessToken());
            return "@" + (username != null ? username : c.instagramBusinessAccountId());
        } catch (MetaSocialNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
