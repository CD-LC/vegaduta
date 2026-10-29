package com.agenticai.core.omnichannel.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import com.agenticai.core.omnichannel.teams.dto.TeamsCredentialResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Teams' card on the Channels screen: the summary shows the bot's app id for an ACTIVE credential
 * only, and the probe mints an Entra token (the same real call activation used to prove the
 * credential).
 */
class TeamsChannelConnectionTest {

    private TeamsCredentialService credentialService;
    private TeamsApiClient apiClient;
    private TeamsChannelConnection connection;

    @BeforeEach
    void setUp() {
        credentialService = mock(TeamsCredentialService.class);
        apiClient = mock(TeamsApiClient.class);
        connection = new TeamsChannelConnection(credentialService, apiClient);
    }

    @Test
    void keyIsTheChannelsScreenKey() {
        assertThat(connection.key()).isEqualTo("teams");
    }

    @Test
    void summaryShowsTheBotAppIdOnlyForAnActiveCredential() {
        when(credentialService.current())
                .thenReturn(
                        Optional.of(
                                new TeamsCredentialResponse(
                                        null,
                                        "app-123",
                                        "aad",
                                        TeamsCredentialStatus.ACTIVE,
                                        null,
                                        null)));

        assertThat(connection.summary().state()).isEqualTo("CONNECTED");
        assertThat(connection.summary().detail()).isEqualTo("bot app-123");
    }

    @Test
    void aDisconnectedOrAbsentCredentialReadsAsNotConnected() {
        when(credentialService.current())
                .thenReturn(
                        Optional.of(
                                new TeamsCredentialResponse(
                                        null,
                                        "app-123",
                                        "aad",
                                        TeamsCredentialStatus.DISCONNECTED,
                                        null,
                                        null)));
        assertThat(connection.summary().state()).isEqualTo("NOT_CONNECTED");

        when(credentialService.current()).thenReturn(Optional.empty());
        assertThat(connection.summary().state()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void probeValidatesTheCredentialAndReportsTheBotAppId() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new TeamsCredentialService.DecryptedCredential(
                                "app-123", "secret", "aad-tenant"));

        assertThat(connection.probe()).isEqualTo("bot app-123");
        verify(apiClient).validateCredentials("app-123", "secret", "aad-tenant");
    }

    @Test
    void probeMapsNotConfiguredToNotConnected() {
        when(credentialService.resolveActive()).thenThrow(new TeamsNotConfiguredException());

        assertThatThrownBy(() -> connection.probe())
                .isInstanceOf(ChannelNotConnectedException.class);
    }
}
