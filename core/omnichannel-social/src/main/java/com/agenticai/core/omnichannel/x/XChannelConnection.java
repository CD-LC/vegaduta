package com.agenticai.core.omnichannel.x;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Component;

/**
 * X's connection state for the Channels screen. A registered app whose account OAuth is not
 * finished needs attention rather than being "not connected".
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class XChannelConnection implements ChannelConnection {

    private final XOAuthService oAuthService;

    public XChannelConnection(XOAuthService oAuthService) {
        this.oAuthService = oAuthService;
    }

    @Override
    public String key() {
        return "x";
    }

    @Override
    public Summary summary() {
        return oAuthService
                .current()
                .map(
                        c ->
                                c.status() == XCredentialStatus.ACTIVE
                                        ? Summary.connected(
                                                c.xUsername() != null
                                                        ? "@" + c.xUsername()
                                                        : "connected")
                                        : Summary.needsAttention(
                                                "app registered - connect account"))
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            // Refreshing the OAuth access token IS the live check: a revoked grant or rotated
            // client
            // secret fails here exactly the way the polling job would fail.
            oAuthService.currentAccessToken();
            return oAuthService.current().map(c -> "@" + c.xUsername()).orElse("connected");
        } catch (XNotConfiguredException | XNotConnectedException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
