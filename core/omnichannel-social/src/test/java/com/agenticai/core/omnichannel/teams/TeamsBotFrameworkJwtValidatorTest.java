package com.agenticai.core.omnichannel.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * SEC-047: a Bot Framework JWT is only accepted when its {@code serviceurl} claim is bound to the
 * activity's serviceUrl and that host is a Microsoft connector host - otherwise the bot's AAD token
 * would be sent to an attacker-chosen serviceUrl on reply.
 */
class TeamsBotFrameworkJwtValidatorTest {

    private static final String APP_ID = "11111111-2222-3333-4444-555555555555";
    private static final String GOOD_URL = "https://smba.trafficmanager.net/amer/";

    private final TeamsServiceUrlPolicy policy =
            new TeamsServiceUrlPolicy(TeamsServiceUrlPolicy.DEFAULT_ALLOWED_HOSTS);

    private static JwtDecoder decoderReturning(Map<String, Object> claims) {
        return token -> {
            if (!"valid-token".equals(token)) {
                throw new BadJwtException("bad signature");
            }
            Jwt.Builder b =
                    Jwt.withTokenValue(token)
                            .header("alg", "RS256")
                            .issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(300));
            claims.forEach(b::claim);
            return b.build();
        };
    }

    private TeamsBotFrameworkJwtValidator validator(Map<String, Object> claims) {
        return new TeamsBotFrameworkJwtValidator(decoderReturning(claims), policy);
    }

    @Test
    void acceptsTokenWhoseServiceUrlClaimMatchesTheActivity() {
        var v = validator(Map.of("aud", APP_ID, "serviceurl", GOOD_URL));
        assertThat(v.isValid("valid-token", APP_ID, GOOD_URL)).isTrue();
        // normalisation: case of scheme/host and trailing slash do not matter
        assertThat(v.isValid("valid-token", APP_ID, "HTTPS://SMBA.trafficmanager.net/amer"))
                .isTrue();
    }

    @Test
    void acceptsCamelCaseClaimAndListAudience() {
        var v = validator(Map.of("aud", List.of(APP_ID), "serviceUrl", GOOD_URL));
        assertThat(v.isValid("valid-token", APP_ID, GOOD_URL)).isTrue();
    }

    @Test
    void rejectsActivityServiceUrlThatDiffersFromTheClaim() {
        // The attack: a genuine token for this bot, activity.serviceUrl swapped to the attacker's
        // host.
        var v = validator(Map.of("aud", APP_ID, "serviceurl", GOOD_URL));
        assertThat(v.isValid("valid-token", APP_ID, "https://attacker.example/")).isFalse();
        assertThat(v.isValid("valid-token", APP_ID, "https://smba.trafficmanager.net/emea/"))
                .isFalse();
        assertThat(v.isValid("valid-token", APP_ID, null)).isFalse();
    }

    @Test
    void rejectsTokenWithoutServiceUrlClaim() {
        var v = validator(Map.of("aud", APP_ID));
        assertThat(v.isValid("valid-token", APP_ID, GOOD_URL)).isFalse();
    }

    @Test
    void rejectsMatchingClaimWhenHostIsNotAllowlisted() {
        // Even if a claim and the activity agree, a non-Microsoft host is refused.
        var v = validator(Map.of("aud", APP_ID, "serviceurl", "https://evil.trafficmanager.net/"));
        assertThat(v.isValid("valid-token", APP_ID, "https://evil.trafficmanager.net/")).isFalse();
        var plainHttp =
                validator(
                        Map.of(
                                "aud",
                                APP_ID,
                                "serviceurl",
                                "http://smba.trafficmanager.net/amer/"));
        assertThat(plainHttp.isValid("valid-token", APP_ID, "http://smba.trafficmanager.net/amer/"))
                .isFalse();
    }

    @Test
    void stillRejectsWrongAudienceAndBadSignature() {
        var v = validator(Map.of("aud", "someone-else", "serviceurl", GOOD_URL));
        assertThat(v.isValid("valid-token", APP_ID, GOOD_URL)).isFalse();
        var ok = validator(Map.of("aud", APP_ID, "serviceurl", GOOD_URL));
        assertThat(ok.isValid("forged", APP_ID, GOOD_URL)).isFalse();
        assertThat(ok.isValid(null, APP_ID, GOOD_URL)).isFalse();
    }

    @Test
    void policyAllowlist() {
        assertThat(policy.isAllowed("https://smba.trafficmanager.net/amer/")).isTrue();
        assertThat(policy.isAllowed("https://webchat.botframework.com/")).isTrue();
        assertThat(policy.isAllowed("https://smba.infra.gcc.teams.microsoft.com/teams/")).isTrue();
        assertThat(policy.isAllowed("https://smba.infra.gov.teams.microsoft.us/")).isTrue();

        assertThat(policy.isAllowed("https://evil.trafficmanager.net/")).isFalse();
        assertThat(policy.isAllowed("https://botframework.com.evil.example/")).isFalse();
        assertThat(policy.isAllowed("https://evilbotframework.com/")).isFalse();
        assertThat(policy.isAllowed("http://smba.trafficmanager.net/amer/")).isFalse();
        assertThat(policy.isAllowed("https://smba.trafficmanager.net:8443/amer/")).isFalse();
        assertThat(policy.isAllowed("https://user@smba.trafficmanager.net/amer/")).isFalse();
        assertThat(policy.isAllowed("https://169.254.169.254/")).isFalse();
        assertThat(policy.isAllowed(null)).isFalse();
        assertThat(policy.isAllowed("not a url")).isFalse();
    }

    @Test
    void apiClientRefusesToSendTheBotTokenToANonAllowlistedServiceUrl() {
        TeamsApiClient client =
                new TeamsApiClient(com.agenticai.core.json.JsonMappers.plain(), policy);
        // Fails before any token mint or network call.
        assertThatThrownBy(
                        () ->
                                client.sendActivity(
                                        APP_ID,
                                        "secret",
                                        "tenant",
                                        "https://attacker.example/",
                                        "conv-1",
                                        "hi",
                                        null))
                .isInstanceOf(ChannelDeliveryException.class)
                .hasMessageContaining("not an allowed Bot Framework host");
    }
}
