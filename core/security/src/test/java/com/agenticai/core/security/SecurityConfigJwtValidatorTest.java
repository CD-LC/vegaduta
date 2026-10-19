package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** SEC-056: the azp check used to wave through any token that simply omitted azp. */
class SecurityConfigJwtValidatorTest {

    private static final String ALLOWED = "agentic-ai-web,agentic-ai-mobile";

    private static Jwt jwt(Consumer<java.util.Map<String, Object>> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).subject("u1");
        b.claims(claims);
        return b.build();
    }

    private static boolean ok(OAuth2TokenValidator<Jwt> v, Jwt jwt) {
        return !v.validate(jwt).hasErrors();
    }

    @Test
    void tokenWithoutAzpIsRejected() {
        assertThat(ok(SecurityConfig.authorizedPartyValidator(ALLOWED), jwt(c -> {}))).isFalse();
    }

    @Test
    void tokenIssuedToAnotherClientIsRejected() {
        assertThat(ok(SecurityConfig.authorizedPartyValidator(ALLOWED), jwt(c -> c.put("azp", "academy-webapp"))))
            .isFalse();
    }

    @Test
    void tokenIssuedToAFirstPartyClientIsAccepted() {
        assertThat(ok(SecurityConfig.authorizedPartyValidator(ALLOWED), jwt(c -> c.put("azp", "agentic-ai-web"))))
            .isTrue();
    }

    @Test
    void emptyAllowlistIsTheDocumentedEmergencyLever() {
        assertThat(ok(SecurityConfig.authorizedPartyValidator(""), jwt(c -> {}))).isTrue();
    }

    @Test
    void audienceCheckIsOffByDefault() {
        assertThat(ok(SecurityConfig.audienceValidator(""), jwt(c -> {}))).isTrue();
        assertThat(ok(SecurityConfig.audienceValidator(null), jwt(c -> {}))).isTrue();
    }

    @Test
    void configuredAudienceRejectsMissingOrWrongAudienceAndAcceptsAMatch() {
        OAuth2TokenValidator<Jwt> v = SecurityConfig.audienceValidator("vegaduta-api");
        assertThat(ok(v, jwt(c -> {}))).isFalse();
        assertThat(ok(v, jwt(c -> c.put("aud", List.of("account"))))).isFalse();
        assertThat(ok(v, jwt(c -> c.put("aud", List.of("account", "vegaduta-api"))))).isTrue();
    }
}
