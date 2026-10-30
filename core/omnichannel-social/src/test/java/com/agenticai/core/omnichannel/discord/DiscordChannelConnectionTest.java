package com.agenticai.core.omnichannel.discord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.discord.dto.DiscordCredentialResponse;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Discord's card on the Channels screen: the summary shows the bot username for an ACTIVE
 * credential only, and the probe is the same {@code fetchBotInfo} call activation used.
 */
class DiscordChannelConnectionTest {

    private DiscordCredentialService credentialService;
    private DiscordApiClient apiClient;
    private DiscordChannelConnection connection;

    @BeforeEach
    void setUp() {
        credentialService = mock(DiscordCredentialService.class);
        apiClient = mock(DiscordApiClient.class);
        connection = new DiscordChannelConnection(credentialService, apiClient);
    }

    private static DiscordCredentialResponse response(
            String botUsername, DiscordCredentialStatus status) {
        return new DiscordCredentialResponse(null, "app-1", botUsername, status, null, null);
    }

    @Test
    void keyIsTheChannelsScreenKey() {
        assertThat(connection.key()).isEqualTo("discord");
    }

    @Test
    void summaryShowsTheBotUsernameOrAFallback() {
        when(credentialService.current())
                .thenReturn(Optional.of(response("vegaduta-bot", DiscordCredentialStatus.ACTIVE)));
        assertThat(connection.summary().state()).isEqualTo("CONNECTED");
        assertThat(connection.summary().detail()).isEqualTo("vegaduta-bot");

        when(credentialService.current())
                .thenReturn(Optional.of(response(null, DiscordCredentialStatus.ACTIVE)));
        assertThat(connection.summary().detail()).isEqualTo("connected");
    }

    @Test
    void aDisconnectedOrAbsentCredentialReadsAsNotConnected() {
        when(credentialService.current())
                .thenReturn(Optional.of(response("bot", DiscordCredentialStatus.DISCONNECTED)));
        assertThat(connection.summary().state()).isEqualTo("NOT_CONNECTED");

        when(credentialService.current()).thenReturn(Optional.empty());
        assertThat(connection.summary().state()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void probeReportsWhoAnsweredPreferringTheUsername() {
        when(credentialService.resolveActive())
                .thenReturn(new DiscordCredentialService.DecryptedCredential("app-1", "bot-token"));
        when(apiClient.fetchBotInfo("bot-token"))
                .thenReturn(new DiscordApiClient.BotInfo("app-1", "vegaduta-bot"));

        assertThat(connection.probe()).isEqualTo("vegaduta-bot");

        when(apiClient.fetchBotInfo("bot-token"))
                .thenReturn(new DiscordApiClient.BotInfo("app-1", null));
        assertThat(connection.probe()).isEqualTo("app-1");
    }

    @Test
    void probeMapsNotConfiguredToNotConnected() {
        when(credentialService.resolveActive()).thenThrow(new DiscordNotConfiguredException());

        assertThatThrownBy(() -> connection.probe())
                .isInstanceOf(ChannelNotConnectedException.class);
    }
}
