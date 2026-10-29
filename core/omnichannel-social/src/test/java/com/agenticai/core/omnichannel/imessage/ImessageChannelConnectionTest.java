package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * iMessage's card on the Channels screen: the summary deliberately shows only the BlueBubbles
 * host (never a credential, and {@code activeServerUrl} decrypts nothing so a key mismatch can't
 * take the status grid down); the probe reports server version and helper state.
 */
class ImessageChannelConnectionTest {

    private ImessageCredentialService credentialService;
    private ImessageApiClient apiClient;
    private ImessageChannelConnection connection;

    @BeforeEach
    void setUp() {
        credentialService = mock(ImessageCredentialService.class);
        apiClient = mock(ImessageApiClient.class);
        connection = new ImessageChannelConnection(credentialService, apiClient);
    }

    @Test
    void keyIsTheChannelsScreenKey() {
        assertThat(connection.key()).isEqualTo("imessage");
    }

    @Test
    void summaryShowsTheBlueBubblesHostOnly() {
        when(credentialService.activeServerUrl())
                .thenReturn(Optional.of("https://bubbles.example.com:1234"));

        assertThat(connection.summary().state()).isEqualTo("CONNECTED");
        assertThat(connection.summary().detail()).isEqualTo("bubbles.example.com");
    }

    @Test
    void summaryFallsBackToConnectedForAnUnparseableUrlAndNotConnectedForNone() {
        when(credentialService.activeServerUrl()).thenReturn(Optional.of("not a url"));
        assertThat(connection.summary().detail()).isEqualTo("connected");

        when(credentialService.activeServerUrl()).thenReturn(Optional.empty());
        assertThat(connection.summary().state()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void probeReportsTheServerVersion() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new ImessageCredentialService.DecryptedCredential(
                                "https://bubbles.example.com", "pw"));
        when(apiClient.fetchServerInfo("https://bubbles.example.com", "pw"))
                .thenReturn(new ImessageApiClient.ServerInfo("1.9.7", "macOS 15", true, true));

        assertThat(connection.probe()).isEqualTo("BlueBubbles v1.9.7");
    }

    @Test
    void probeFlagsAnOfflinePrivateApiHelperAndAMissingVersion() {
        when(credentialService.resolveActive())
                .thenReturn(
                        new ImessageCredentialService.DecryptedCredential(
                                "https://bubbles.example.com", "pw"));
        when(apiClient.fetchServerInfo("https://bubbles.example.com", "pw"))
                .thenReturn(new ImessageApiClient.ServerInfo(null, "macOS 15", false, false));

        assertThat(connection.probe())
                .isEqualTo("BlueBubbles (Private API helper offline)");
    }

    @Test
    void probeMapsNotConfiguredToNotConnected() {
        when(credentialService.resolveActive()).thenThrow(new ImessageNotConfiguredException());

        assertThatThrownBy(() -> connection.probe())
                .isInstanceOf(ChannelNotConnectedException.class);
    }
}
