package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        Jwt.Builder b =
                Jwt.withTokenValue("t")
                        .header("alg", "RS256")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .subject("u1");
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
        assertThat(
                        ok(
                                SecurityConfig.authorizedPartyValidator(ALLOWED),
                                jwt(c -> c.put("azp", "academy-webapp"))))
                .isFalse();
    }

    @Test
    void tokenIssuedToAFirstPartyClientIsAccepted() {
        assertThat(
                        ok(
                                SecurityConfig.authorizedPartyValidator(ALLOWED),
                                jwt(c -> c.put("azp", "agentic-ai-web"))))
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

    // --- SEC-056 follow-up: generic-OIDC issuers must keep at least one client-bound check ---

    @Test
    void aGenericOidcIssuerWithBothChecksEmptyFailsStartup() {
        assertThatThrownBy(
                        () ->
                                SecurityConfig.requireClientCheckForNonKeycloakIssuer(
                                        "https://tenant.auth0.com/", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-azp")
                .hasMessageContaining("required-audience");
    }

    @Test
    void aGenericOidcIssuerWithAzpConfiguredPasses() {
        SecurityConfig.requireClientCheckForNonKeycloakIssuer(
                "https://tenant.auth0.com/", "my-oidc-client", "");
    }

    @Test
    void aGenericOidcIssuerWithAudienceConfiguredPasses() {
        SecurityConfig.requireClientCheckForNonKeycloakIssuer(
                "https://login.microsoftonline.com/tenant/v2.0", "", "vegaduta-api");
    }

    @Test
    void aKeycloakStyleIssuerKeepsTheEmergencyLever() {
        // The default azp list is Keycloak-specific; emptying it is the documented lever, and a
        // Keycloak issuer (always /realms/) must keep working with both lists empty.
        SecurityConfig.requireClientCheckForNonKeycloakIssuer(
                "http://localhost:8180/realms/agentic-ai", "", "");
    }

    @Test
    void theExplicitProviderWinsOverTheIssuerUriShape() {
        // A realm-shaped URI declared generic is NOT exempt ...
        assertThatThrownBy(
                        () ->
                                SecurityConfig.requireClientCheckForNonKeycloakIssuer(
                                        "generic", "https://idp.example.com/realms/shared", "", "", false))
                .isInstanceOf(IllegalStateException.class);
        // ... and a non-realm URI declared keycloak (a reverse-proxied Keycloak) is.
        SecurityConfig.requireClientCheckForNonKeycloakIssuer(
                "KeyCloak", "https://sso.example.com/auth", "", "", false);
    }

    @Test
    void genericProviderCanOptOutOfTheGuard() {
        SecurityConfig.requireClientCheckForNonKeycloakIssuer(
                "generic", "https://tenant.auth0.com/", "", "", true);
    }

    @Test
    void anUnsetProviderIsDerivedFromTheIssuerUri() {
        assertThat(SecurityConfig.resolveProvider("", "http://kc/realms/agentic-ai"))
                .isEqualTo(SecurityConfig.OidcProvider.KEYCLOAK);
        assertThat(SecurityConfig.resolveProvider(null, "https://tenant.auth0.com/"))
                .isEqualTo(SecurityConfig.OidcProvider.GENERIC);
    }

    @Test
    void anUnknownProviderValueFailsStartup() {
        assertThatThrownBy(() -> SecurityConfig.resolveProvider("okta", "https://x/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keycloak' or 'generic");
    }
}
