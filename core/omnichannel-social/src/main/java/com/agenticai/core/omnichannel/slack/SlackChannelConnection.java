package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/** Slack's connection state for the Channels screen: the workspace name. */
@Component
public class SlackChannelConnection implements ChannelConnection {

    private final SlackCredentialService credentialService;
    private final SlackApiClient apiClient;

    public SlackChannelConnection(
            SlackCredentialService credentialService, SlackApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String key() {
        return "slack";
    }

    @Override
    public Summary summary() {
        return credentialService
                .current()
                .filter(c -> c.status() == SlackCredentialStatus.ACTIVE)
                .map(c -> Summary.connected(c.teamName() != null ? c.teamName() : "connected"))
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            SlackCredentialService.DecryptedCredential c = credentialService.resolveActive();
            SlackApiClient.AuthInfo info = apiClient.authTest(c.botToken());
            return info.teamName() != null ? info.teamName() : info.teamId();
        } catch (SlackNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
