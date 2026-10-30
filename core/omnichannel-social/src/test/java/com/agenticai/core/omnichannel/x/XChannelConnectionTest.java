package com.agenticai.core.omnichannel.x;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import com.agenticai.core.omnichannel.x.dto.XConnectionResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * X's card on the Channels screen: an app whose OAuth consent never finished is NEEDS_ATTENTION
 * rather than NOT_CONNECTED, and the probe is a real access-token refresh - a revoked grant fails
 * there exactly the way the polling job would fail.
 */
class XChannelConnectionTest {

    private XOAuthService oAuthService;
    private XChannelConnection connection;

    @BeforeEach
    void setUp() {
        oAuthService = mock(XOAuthService.class);
        connection = new XChannelConnection(oAuthService);
    }

    private static XConnectionResponse response(String username, XCredentialStatus status) {
        return new XConnectionResponse(null, "client-id", username, status, null, null);
    }

    @Test
    void keyIsTheChannelsScreenKey() {
        assertThat(connection.key()).isEqualTo("x");
    }

    @Test
    void summaryShowsTheConnectedHandleForAnActiveCredential() {
        when(oAuthService.current())
                .thenReturn(Optional.of(response("acme", XCredentialStatus.ACTIVE)));

        assertThat(connection.summary().state()).isEqualTo("CONNECTED");
        assertThat(connection.summary().detail()).isEqualTo("@acme");
    }

    @Test
    void anActiveCredentialWithoutAUsernameStillReadsConnected() {
        when(oAuthService.current())
                .thenReturn(Optional.of(response(null, XCredentialStatus.ACTIVE)));

        assertThat(connection.summary().detail()).isEqualTo("connected");
    }

    @Test
    void aRegisteredButUnconnectedAppNeedsAttention() {
        when(oAuthService.current())
                .thenReturn(Optional.of(response(null, XCredentialStatus.APP_REGISTERED)));

        assertThat(connection.summary().state()).isEqualTo("NEEDS_ATTENTION");
    }

    @Test
    void noCredentialAtAllReadsAsNotConnected() {
        when(oAuthService.current()).thenReturn(Optional.empty());

        assertThat(connection.summary().state()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void probeRefreshesTheTokenAndReportsTheHandle() {
        when(oAuthService.currentAccessToken()).thenReturn("access-token");
        when(oAuthService.current())
                .thenReturn(Optional.of(response("acme", XCredentialStatus.ACTIVE)));

        assertThat(connection.probe()).isEqualTo("@acme");
    }

    @Test
    void probeFallsBackAndMapsOAuthFailuresToNotConnected() {
        when(oAuthService.currentAccessToken()).thenReturn("access-token");
        when(oAuthService.current()).thenReturn(Optional.empty());
        assertThat(connection.probe()).isEqualTo("connected");

        doThrow(new XNotConfiguredException()).when(oAuthService).currentAccessToken();
        assertThatThrownBy(() -> connection.probe())
                .isInstanceOf(ChannelNotConnectedException.class);

        doThrow(new XNotConnectedException()).when(oAuthService).currentAccessToken();
        assertThatThrownBy(() -> connection.probe())
                .isInstanceOf(ChannelNotConnectedException.class);
    }
}
