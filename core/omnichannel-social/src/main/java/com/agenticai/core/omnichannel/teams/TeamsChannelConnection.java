package com.agenticai.core.omnichannel.teams;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/** Microsoft Teams' connection state for the Channels screen: the bot's app id. */
@Component
public class TeamsChannelConnection implements ChannelConnection {

    private final TeamsCredentialService credentialService;
    private final TeamsApiClient apiClient;

    public TeamsChannelConnection(
            TeamsCredentialService credentialService, TeamsApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String key() {
        return "teams";
    }

    @Override
    public Summary summary() {
        return credentialService
                .current()
                .filter(c -> c.status() == TeamsCredentialStatus.ACTIVE)
                .map(c -> Summary.connected("bot " + c.appId()))
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            TeamsCredentialService.DecryptedCredential c = credentialService.resolveActive();
            apiClient.validateCredentials(c.appId(), c.appPassword(), c.aadTenantId());
            return "bot " + c.appId();
        } catch (TeamsNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
