package com.agenticai.core.security.localauth;

import java.time.Clock;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Wiring of local single-user auth ({@code app.auth.mode=local}). Present ONLY in that mode: with
 * the default {@code keycloak} mode none of these beans exist and {@code SecurityConfig} builds the
 * exact Keycloak resource server it always did (pinned by {@code AuthModeWiringTest}).
 *
 * <p>What it contributes, and what it deliberately does not:
 *
 * <ul>
 *   <li>the {@link JwtDecoder} the unchanged main filter chain consumes - so every existing
 *       endpoint authorizes against locally issued tokens through the same role converter, tenant
 *       filter and URL rules it uses for Keycloak tokens;
 *   <li>a dedicated chain for {@code /api/auth/local/**}, ordered ahead of the main chain (the same
 *       pattern as the Alertmanager and MCP chains). Login, refresh, logout, setup and the public
 *       config are open by nature (the caller has no token yet); change-password and the
 *       account-management endpoints under {@code /unlock} ({@link #BEARER_REQUIRED}) need a valid
 *       access token, and passkey registration accepts either one or a setup token
 *       ({@link #BEARER_OR_SETUP_TOKEN}). Every method on the controller also carries an explicit
 *       {@code @PreAuthorize}, which the authorization-coverage test requires;
 *   <li>a second dedicated chain that closes {@code POST /api/signup}. That endpoint is public by
 *       necessity in the hosted product (nobody has a token yet), and it provisions a whole new
 *       tenant plus a tenant-admin account. On a single-owner install it would let anyone who can
 *       reach the port create a second workspace on the owner's machine. The first-run owner is
 *       created through the provisioning service directly, never through this endpoint, and
 *       teammates are added by invitation, so nothing legitimate needs it;
 *   <li>no change to {@code PublicSurfaceRules}, so the pinned main-chain snapshot is untouched.
 * </ul>
 */
@Configuration
@Profile("!insecure")
@ConditionalOnProperty(name = LocalAuth.PROPERTY, havingValue = LocalAuth.LOCAL)
public class LocalAuthSecurityConfig {

    /** Path prefix of the local auth endpoints. */
    public static final String BASE_PATH = "/api/auth/local";

    /** Public self-service sign-up of the hosted product; closed in local mode. */
    static final String SIGNUP_PATH = "/api/signup";

    private static final String CHANGE_PASSWORD_PATH = BASE_PATH + "/change-password";

    /** Passkey, device-PIN and recovery-code endpoints ({@code LocalUnlockController}). */
    private static final String UNLOCK_PATH = BASE_PATH + "/unlock";

    /**
     * Paths that need a valid access token: the caller is already signed in and is managing the ways
     * to sign in. Used both to RESOLVE the bearer and to REQUIRE authentication, so the two cannot drift.
     */
    static final RequestMatcher BEARER_REQUIRED =
            anyOf(
                    CHANGE_PASSWORD_PATH,
                    UNLOCK_PATH + "/methods",
                    UNLOCK_PATH + "/devices",
                    UNLOCK_PATH + "/devices/*",
                    UNLOCK_PATH + "/passkeys/*",
                    UNLOCK_PATH + "/recovery-codes");

    /**
     * Passkey registration works for a signed-in person AND, during first-run/invite setup, for
     * someone holding only a setup token: a bearer is validated when one is sent, and an anonymous
     * call is let through to the handler, which then insists on a valid setup token.
     */
    static final RequestMatcher BEARER_OR_SETUP_TOKEN =
            anyOf(UNLOCK_PATH + "/passkey/register-options", UNLOCK_PATH + "/passkey/register");

    private static RequestMatcher anyOf(String... patterns) {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(Arrays.stream(patterns).map(paths::matcher).toArray(RequestMatcher[]::new));
    }

    @Bean
    public LocalAuthSettings localAuthSettings(Environment environment) {
        return LocalAuthSettings.from(environment);
    }

    @Bean
    public LocalSigningKeyStore localSigningKeyStore(LocalAuthSettings settings) {
        return new LocalSigningKeyStore(
                settings.dataDir(),
                settings.keyRotationInterval(),
                settings.keyRetention(),
                Clock.systemUTC());
    }

    @Bean
    public LocalJwtService localJwtService(LocalAuthSettings settings, LocalSigningKeyStore keys) {
        return new LocalJwtService(settings, keys, Clock.systemUTC());
    }

    @Bean
    public LoginThrottle localLoginThrottle() {
        return new LoginThrottle(Clock.systemUTC());
    }

    /** The one {@link JwtDecoder} in local mode; {@code SecurityConfig}'s Keycloak one is absent. */
    @Bean
    public JwtDecoder localJwtDecoder(LocalJwtService tokens) {
        return tokens.decoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain localSignupClosedFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(SIGNUP_PATH)
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll());
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain localAuthFilterChain(HttpSecurity http, JwtDecoder localJwtDecoder)
            throws Exception {
        // A bearer token is only looked at where it is needed. On login/refresh/setup (and the
        // unlock sign-in endpoints) a stale Authorization header from an expired session must not
        // turn a valid sign-in into a 401.
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        BearerTokenResolver onlyWhereNeeded =
                request -> {
                    if (BEARER_REQUIRED.matches(request) || BEARER_OR_SETUP_TOKEN.matches(request)) {
                        return defaultResolver.resolve(request);
                    }
                    return null;
                };
        http.securityMatcher(BASE_PATH + "/**")
                .csrf(csrf -> csrf.disable()) // stateless: bearer / body credentials, no cookies
                .cors(Customizer.withDefaults())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .requestMatchers(BEARER_REQUIRED)
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.bearerTokenResolver(onlyWhereNeeded)
                                        .jwt(jwt -> jwt.decoder(localJwtDecoder)));
        return http.build();
    }
}
