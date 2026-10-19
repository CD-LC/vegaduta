package com.agenticai.core.security.localauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.SecurityConfig;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * The regression guard for the mode switch: with {@code app.auth.mode} unset or {@code keycloak} the
 * security wiring is exactly what it was before local mode existed (one Keycloak {@code jwtDecoder},
 * the same three filter chains, none of the local beans); with {@code local} it boots with NO
 * Keycloak property at all and swaps only the decoder and adds one chain.
 */
class AuthModeWiringTest {

    @EnableWebMvc
    @Configuration(proxyBeanMethods = false)
    static class MinimalWeb {}

    private static final String ISSUER = "spring.security.oauth2.resourceserver.jwt.issuer-uri";
    private static final String JWKS = "app.keycloak.jwk-set-uri";

    private static AnnotationConfigWebApplicationContext boot(Map<String, Object> props, Class<?>... extra) {
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", props));
        ctx.register(MinimalWeb.class, SecurityConfig.class, LocalAuthSecurityConfig.class, AuthModeValidator.class);
        if (extra.length > 0) {
            ctx.register(extra);
        }
        ctx.refresh();
        return ctx;
    }

    private static Map<String, Object> keycloakProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ISSUER, "http://keycloak.invalid/realms/test");
        props.put(JWKS, "http://keycloak.invalid/realms/test/protocol/openid-connect/certs");
        return props;
    }

    @Test
    void defaultModeIsKeycloakAndWiringIsUnchanged() {
        try (AnnotationConfigWebApplicationContext ctx = boot(keycloakProps())) {
            assertThat(ctx.getBeansOfType(JwtDecoder.class)).containsOnlyKeys("jwtDecoder");
            assertThat(ctx.containsBean("localJwtService")).isFalse();
            assertThat(ctx.containsBean("localAuthFilterChain")).isFalse();
            assertThat(ctx.getBeansOfType(SecurityFilterChain.class))
                    .containsOnlyKeys("alertWebhookFilterChain", "mcpServerFilterChain", "filterChain");
        }
    }

    @Test
    void anExplicitKeycloakModeIsTheSameAsTheDefault() {
        Map<String, Object> props = keycloakProps();
        props.put("app.auth.mode", "keycloak");
        try (AnnotationConfigWebApplicationContext ctx = boot(props)) {
            assertThat(ctx.getBeansOfType(JwtDecoder.class)).containsOnlyKeys("jwtDecoder");
            assertThat(ctx.getBeansOfType(SecurityFilterChain.class)).hasSize(3);
        }
    }

    @Test
    void keycloakModeStillRefusesToBootWithoutAnIssuer() {
        assertThatThrownBy(() -> boot(new HashMap<>()))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                        "app.auth.mode=keycloak needs spring.security.oauth2.resourceserver.jwt.issuer-uri"
                                + " (KEYCLOAK_ISSUER_URI) and app.keycloak.jwk-set-uri"
                                + " (KEYCLOAK_JWK_SET_URI); set them, or use app.auth.mode=local");
    }

    @Test
    void localModeBootsWithoutAnyKeycloakPropertyAndSwapsOnlyTheDecoder(@TempDir Path dir) {
        Map<String, Object> props = new HashMap<>();
        props.put("app.auth.mode", "local");
        props.put("app.auth.local.data-dir", dir.toString());
        try (AnnotationConfigWebApplicationContext ctx = boot(props)) {
            assertThat(ctx.getBeansOfType(JwtDecoder.class)).containsOnlyKeys("localJwtDecoder");
            assertThat(ctx.getBeansOfType(SecurityFilterChain.class))
                    .containsOnlyKeys(
                            "alertWebhookFilterChain",
                            "mcpServerFilterChain",
                            "filterChain",
                            "localAuthFilterChain",
                            "localSignupClosedFilterChain");
            assertThat(ctx.getBean(LocalAuthSettings.class).dataDir()).isEqualTo(dir);
        }
    }

    @Test
    void localModeAcceptsATokenItIssuedThroughTheContextDecoder(@TempDir Path dir) {
        Map<String, Object> props = new HashMap<>();
        props.put("app.auth.mode", "local");
        props.put("app.auth.local.data-dir", dir.toString());
        try (AnnotationConfigWebApplicationContext ctx = boot(props)) {
            String token =
                    ctx.getBean(LocalJwtService.class)
                            .issue(
                                    new LocalJwtService.AccessTokenRequest(
                                            "u1", "a@b.test", "A", "B", "t_x", java.util.Set.of("tenant-admin"), "s", null, "pwd"))
                            .token();
            assertThat(ctx.getBean("localJwtDecoder", JwtDecoder.class).decode(token).getSubject()).isEqualTo("u1");
        }
    }

    @Test
    void anUnknownModeFailsTheBootNamingTheProperty() {
        Map<String, Object> props = keycloakProps();
        props.put("app.auth.mode", "oidc");
        assertThatThrownBy(() -> boot(props)).hasStackTraceContaining("app.auth.mode must be 'keycloak' or 'local'");
    }

    @Test
    void modeParsingIsCaseAndWhitespaceTolerantButClosed() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        assertThat(LocalAuth.mode(env)).isEqualTo("keycloak");
        env.setProperty("app.auth.mode", "  LOCAL ");
        assertThat(LocalAuth.isLocal(env)).isTrue();
        env.setProperty("app.auth.mode", " ");
        assertThat(LocalAuth.mode(env)).isEqualTo("keycloak");
        env.setProperty("app.auth.mode", "ldap");
        assertThatThrownBy(() -> LocalAuth.mode(env)).isInstanceOf(IllegalStateException.class);
    }
}
