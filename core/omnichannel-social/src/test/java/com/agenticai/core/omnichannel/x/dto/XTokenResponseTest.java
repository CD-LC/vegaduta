package com.agenticai.core.omnichannel.x.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The X admin DTOs: {@link XAppRegisterRequest} is validated server-side; {@link
 * XConnectRequest} carries the OAuth state/codeVerifier the controller needs to finish the
 * PKCE exchange; {@link XTokenResponse} is the raw token-endpoint body with its
 * {@code @JsonProperty} names matching X's snake_case wire format.
 */
class XTokenResponseTest {

    @Test
    void appRegisterRequestExposesClientIdAndSecret() {
        XAppRegisterRequest request = new XAppRegisterRequest("client-id", "client-secret");

        assertThat(request.xClientId()).isEqualTo("client-id");
        assertThat(request.xClientSecret()).isEqualTo("client-secret");
    }

    @Test
    void connectRequestExposesTheCallbackParameters() {
        XConnectRequest request = new XConnectRequest("state-1", "auth-code", "verifier");

        assertThat(request.state()).isEqualTo("state-1");
        assertThat(request.code()).isEqualTo("auth-code");
        assertThat(request.codeVerifier()).isEqualTo("verifier");
    }

    @Test
    void tokenResponseExposesTheWireFields() {
        XTokenResponse response =
                new XTokenResponse("access", "refresh", "bearer", 7200, "dm.write tweet.read");

        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.tokenType()).isEqualTo("bearer");
        assertThat(response.expiresIn()).isEqualTo(7200);
        assertThat(response.scope()).isEqualTo("dm.write tweet.read");
    }
}
