package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/** Discord's connection state for the Channels screen: the bot's username. */
@Component
public class DiscordChannelConnection implements ChannelConnection {

    private final DiscordCredentialService credentialService;
    private final DiscordApiClient apiClient;

    public DiscordChannelConnection(
            DiscordCredentialService credentialService, DiscordApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String key() {
        return "discord";
    }

    @Override
    public Summary summary() {
        return credentialService
                .current()
                .filter(c -> c.status() == DiscordCredentialStatus.ACTIVE)
                .map(
                        c ->
                                Summary.connected(
                                        c.botUsername() != null ? c.botUsername() : "connected"))
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            DiscordCredentialService.DecryptedCredential c = credentialService.resolveActive();
            DiscordApiClient.BotInfo info = apiClient.fetchBotInfo(c.botToken());
            return info.username() != null ? info.username() : info.applicationId();
        } catch (DiscordNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
