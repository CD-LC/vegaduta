package com.agenticai.core.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link LiveKitTokenService} signs a token that actually satisfies
 * LiveKit's documented access-token contract (HS256, correct issuer/subject,
 * a {@code video} grant scoped to the requested room) - the one piece of
 * this feature that can be fully verified WITHOUT a running LiveKit server
 * or a live core deployment, so it's covered here rather than left as an
 * unverified claim.
 */
class LiveKitTokenServiceTest {

    private static final String API_KEY = "test-key";
    private static final String API_SECRET = "test-secret-at-least-32-bytes-long!!";

    @Test
    void issueToken_producesAValidHs256JwtWithTheExpectedGrant() throws Exception {
        LiveKitTokenService service = new LiveKitTokenService(API_KEY, API_SECRET, "ws://localhost:7880", 3600);

        String token = service.issueToken("tenant-1:alice:abcd1234", "voice-room-1", "alice");

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.verify(new MACVerifier(API_SECRET.getBytes()))).isTrue();

        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo(API_KEY);
        assertThat(claims.getSubject()).isEqualTo("tenant-1:alice:abcd1234");
        assertThat(claims.getStringClaim("name")).isEqualTo("alice");
        assertThat(claims.getExpirationTime()).isAfter(claims.getIssueTime());

        @SuppressWarnings("unchecked")
        Map<String, Object> videoGrant = (Map<String, Object>) claims.getClaim("video");
        assertThat(videoGrant.get("room")).isEqualTo("voice-room-1");
        assertThat(videoGrant.get("roomJoin")).isEqualTo(true);
        assertThat(videoGrant.get("canPublish")).isEqualTo(true);
        assertThat(videoGrant.get("canSubscribe")).isEqualTo(true);
    }

    @Test
    void issueToken_throwsWhenUnconfigured() {
        LiveKitTokenService service = new LiveKitTokenService("", "", "ws://localhost:7880", 3600);

        assertThatThrownBy(() -> service.issueToken("id", "room", "name"))
            .isInstanceOf(LiveKitNotConfiguredException.class);
    }

    @Test
    void isConfigured_isFalseWhenKeyOrSecretIsBlank() {
        assertThat(new LiveKitTokenService("", API_SECRET, "ws://localhost:7880", 3600).isConfigured()).isFalse();
        assertThat(new LiveKitTokenService(API_KEY, "", "ws://localhost:7880", 3600).isConfigured()).isFalse();
        assertThat(new LiveKitTokenService(API_KEY, API_SECRET, "ws://localhost:7880", 3600).isConfigured()).isTrue();
    }
}
