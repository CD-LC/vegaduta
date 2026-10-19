package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/** Telegram's connection state for the Channels screen: the bot's {@code @username}. */
@Component
public class TelegramChannelConnection implements ChannelConnection {

    private final TelegramCredentialService credentialService;
    private final TelegramApiClient apiClient;

    public TelegramChannelConnection(
            TelegramCredentialService credentialService, TelegramApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String key() {
        return "telegram";
    }

    @Override
    public Summary summary() {
        return credentialService
                .current()
                .filter(c -> c.status() == TelegramCredentialStatus.ACTIVE)
                .map(
                        c ->
                                Summary.connected(
                                        c.botUsername() != null
                                                ? "@" + c.botUsername()
                                                : "connected"))
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            TelegramCredentialService.DecryptedCredential c = credentialService.resolveActive();
            TelegramApiClient.BotInfo info = apiClient.fetchBotInfo(c.botToken());
            return "@" + (info.username() != null ? info.username() : c.botUserId());
        } catch (TelegramNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
