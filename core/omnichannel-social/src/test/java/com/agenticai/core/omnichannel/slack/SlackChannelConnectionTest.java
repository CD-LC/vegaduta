package com.agenticai.core.omnichannel.slack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.slack.dto.SlackCredentialResponse;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slack's card on the Channels screen: the summary counts ACTIVE credentials only (a
 * DISCONNECTED row must not read as connected), and the probe is the same real auth.test call
 * activation used.
 */
class SlackChannelConnectionTest {

    private SlackCredentialService credentialService;
    private SlackApiClient apiClient;
    private SlackChannelConnection connection;

    @BeforeEach
    void setUp() {
        credentialService = mock(SlackCredentialService.class);
        apiClient = mock(SlackApiClient.class);
        connection = new SlackChannelConnection(credentialService, apiClient);
    }

    private static SlackCredentialResponse response(
            String teamName, SlackCredentialStatus status) {
        return new SlackCredentialResponse(null, "T123", teamName, status, null, null);
    }

    @Test
    void keyIsTheChannelsScreenKey() {
        assertThat(connection.key()).isEqualTo("slack");
    }

    @Test
    void summaryShowsTheWorkspaceNameForAnActiveCredential() {
        when(credentialService.current())
                .thenReturn(Optional.of(response("Acme Corp", SlackCredentialStatus.ACTIVE)));

        assertThat(connection.summary().state()).isEqualTo("CONNECTED");
        assertThat(connection.summary().detail()).isEqualTo("Acme Corp");
    }

    @Test
    void summaryFallsBackToAConnectedPlaceholderWithoutATeamName() {
        when(credentialService.current())
                .thenReturn(Optional.of(response(null, SlackCredentialStatus.ACTIVE)));

        assertThat(connection.summary().detail()).isEqualTo("connected");
    }

    @Test
    void aDisconnectedOrAbsentCredentialReadsAsNotConnected() {
        when(credentialService.current())
                .thenReturn(Optional.of(response("Acme", SlackCredentialStatus.DISCONNECTED)));
        assertThat(connection.summary().state()).isEqualTo("NOT_CONNECTED");

        when(credentialService.current()).thenReturn(Optional.empty());
        assertThat(connection.summary().state()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void probeReportsWhoAnsweredPreferringTheTeamName() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new SlackCredentialService.DecryptedCredential(
                                "T123", "UBOT", "xoxb", "secret"));
        when(apiClient.authTest("xoxb"))
                .thenReturn(new SlackApiClient.AuthInfo("T123", "Acme Corp", "UBOT"));

        assertThat(connection.probe()).isEqualTo("Acme Corp");
    }

    @Test
    void probeFallsBackToTheTeamIdAndMapsNotConfiguredToNotConnected() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new SlackCredentialService.DecryptedCredential(
                                "T123", "UBOT", "xoxb", "secret"));
        when(apiClient.authTest("xoxb"))
                .thenReturn(new SlackApiClient.AuthInfo("T123", null, "UBOT"));
        assertThat(connection.probe()).isEqualTo("T123");

        when(credentialService.resolveActive()).thenThrow(new SlackNotConfiguredException());
        assertThatThrownBy(() -> connection.probe())
                .isInstanceOf(ChannelNotConnectedException.class);
    }
}
