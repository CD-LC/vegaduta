package com.agenticai.core.security;

import com.agenticai.core.security.apikey.ApiKeyAuthFilter;
import com.agenticai.core.security.apikey.TenantApiKeyAuthenticator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * Phase 0 Stage 3 - OAuth2 Resource Server validating Keycloak-issued JWTs.
 *
 * <p><b>This chain is now the DEFAULT ({@code @Profile("!insecure")})</b> - inverted from the
 * original {@code @Profile("security")} gating by the 2026-07-29 security audit so that the absence
 * of any profile fails CLOSED instead of open (see {@link NoSecurityConfig}'s javadoc for the full
 * finding). The {@code security} profile still matters, but for a different reason:
 * application-security.yml (the {@code issuer-uri}/{@code jwk-set-uri} values injected below, plus
 * every other {@code @Profile("security")} bean - signup, billing, platform admin, Keycloak admin
 * client) only loads when it is active. Run WITHOUT {@code security} and WITHOUT {@code insecure} -
 * the typo'd-SPRING_PROFILES_ACTIVE scenario - and this class still registers but its
 * {@code @Value} placeholders cannot resolve, so startup fails loudly right here rather than
 * serving an unauthenticated API. That boot failure is deliberate; do not "fix" it by giving these
 * placeholders defaults. The only ways to boot are the real posture ({@code security} active) or an
 * explicit opt-in to no auth ({@code insecure} active, dev/CI only). See {@link NoSecurityConfig}'s
 * javadoc for why a companion permissive config must exist at all - without one registered {@code
 * SecurityFilterChain} in the {@code insecure} case, Spring Boot's own {@code
 * SecurityAutoConfiguration} would register a default HTTP-Basic-with-random-password chain over
 * every endpoint the moment {@code spring-boot-starter-security} is on the classpath, independent
 * of any profile.
 *
 * <p><b>Bug caught before shipping the web console's login integration:</b> {@link
 * com.agenticai.core.common.WebConfig}'s CORS mapping is registered via {@code WebMvcConfigurer},
 * which only governs Spring MVC's {@code DispatcherServlet} handling - Spring Security's filter
 * chain runs in front of that and, without an explicit {@code .cors(...)} call here, does not
 * consult it at all. With {@code security} active and no fix, every cross-origin browser preflight
 * (OPTIONS) or bearer-token request from the Next.js console at :3000 would have hit {@code
 * anyRequest() .authenticated()} and been rejected before Spring MVC's CORS handling ever ran - the
 * browser would report a generic CORS failure that looks nothing like "add a token," making it a
 * nasty one to debug blind. {@code .cors(Customizer.withDefaults())} tells Spring Security to
 * source its CORS decision from the same {@code CorsConfigurationSource} Spring MVC already built
 * from {@code WebConfig}, via the auto-configured {@code HandlerMappingIntrospector} bean - one
 * CORS policy, not two to keep in sync.
 *
 * <p><b>Method security</b> ({@code @EnableMethodSecurity}, JAVA-006): URL matchers here stay the
 * primary authorization mechanism, but a handler may now also carry {@code @PreAuthorize}. {@code
 * AuthorizationCoverageTest} (core/app) fails the build when a new mutating endpoint has neither a
 * specific matcher here nor a method-level rule, unless it is on the reviewed allowlist.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Profile("!insecure")
public class SecurityConfig {

    // Read lazily in jwtDecoder(), not through @Value fields: in app.auth.mode=local there is no
    // Keycloak, these properties are unset (or, with the `security` profile, hold a placeholder
    // that cannot resolve), and an eager @Value would stop the boot for a decoder nobody builds.
    // In the default keycloak mode jwtDecoder() still refuses to build without them, so a missing
    // KEYCLOAK_ISSUER_URI fails the boot exactly as it always did.
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.core.env.Environment environment;

    /**
     * Optional comma-separated list of ADDITIONAL accepted token issuers, on top of {@link
     * #issuerUri}. Empty by default, in which case validation is the single-issuer behaviour
     * exactly as before. It exists for multi-hostname deployments where the SAME Keycloak realm is
     * reached over more than one public host under {@code KC_HOSTNAME_STRICT=false} - each host
     * mints tokens carrying ITS OWN host in the {@code iss} claim (e.g. a browser that logged in at
     * {@code https://auth.staging.example.com} gets {@code
     * iss=https://auth.staging.example.com/realms/...}, while a localhost session gets {@code
     * iss=http://localhost:8180/realms/...}). Because it is the same realm, the signing keys are
     * identical, so a single {@code jwkSetUri} still validates every signature - only the {@code
     * iss} check needs to accept the set. Set via {@code KEYCLOAK_ADDITIONAL_ISSUERS}.
     */
    @Value("${app.keycloak.additional-issuers:}")
    private String additionalIssuers;

    /**
     * Deliberately NOT the default {@code withIssuerLocation(issuerUri)} auto-configuration - that
     * fetches the JWKS from {@code issuerUri} itself AND requires Keycloak's own discovery
     * document, fetched from that same address, to self-report that exact address as its issuer.
     * Inside Docker, core reaches Keycloak at {@code keycloak:8080} but Keycloak's issuer is pinned
     * (KC_HOSTNAME_STRICT) to the public-facing {@code localhost:8180} - those can never be the
     * same address, so {@code withIssuerLocation} can never succeed here. Splitting "where to fetch
     * keys from" ({@code jwkSetUri}, container-reachable) from "what issuer to validate tokens
     * against" ({@code issuerUri}, the public-facing value actually embedded in every real token's
     * {@code iss} claim) is what {@code JwtValidators.createDefaultWithIssuer} is for - see
     * application-security.yml's app.keycloak.jwk-set-uri comment for the full failure mode this
     * replaces.
     */
    /**
     * Keycloak clients whose tokens this API accepts (matched against the token's {@code azp}).
     * Every first-party client that calls core is listed; {@code academy-webapp} deliberately is
     * not. A deployment that adds a client sets {@code APP_KEYCLOAK_ALLOWED_AZP}; setting it EMPTY
     * switches the check off (the pre-2026-09-18 behaviour) as an emergency lever.
     */
    @org.springframework.beans.factory.annotation.Value(
            "${app.keycloak.allowed-azp:agentic-ai-web,agentic-ai-platform-admin,agentic-ai-mobile,agentic-ai-app,agentic-ai-ios,agentic-ai-ide,agentic-ai-core}")
    private String allowedAuthorizedParties = "";

    /**
     * SEC-056: optional comma-separated audience list. When non-empty, a token must carry at least
     * one of these values in {@code aud} (Keycloak adds an audience via an "Audience" protocol
     * mapper on the client scope). Empty (the default) switches the check off, because stock
     * Keycloak access tokens for public clients carry only {@code aud=account}. Env: {@code
     * APP_SECURITY_JWT_REQUIRED_AUDIENCE}.
     */
    @Value("${app.security.jwt.required-audience:}")
    private String requiredAudience = "";

    /**
     * Generic-OIDC claim mapping ({@link OidcClaimMapping}) - where roles, tenant and (optionally)
     * scopes live in the token. The defaults reproduce the Keycloak layout EXACTLY ({@code
     * realm_access.roles}, {@code tenant_id}, no scope mapping), so an unconfigured deployment is
     * unchanged; point {@code issuer-uri} at Auth0/Entra/Okta and set these to that provider's
     * claim names. See application-security.yml's {@code app.security.oidc} block. Env: {@code
     * OIDC_ROLES_CLAIM_PATH}, {@code OIDC_TENANT_CLAIM}, {@code OIDC_SCOPES_CLAIM}.
     */
    @Value("${app.security.oidc.roles-claim-path:realm_access.roles}")
    private String rolesClaimPath;

    @Value("${app.security.oidc.tenant-claim:tenant_id}")
    private String tenantClaim;

    @Value("${app.security.oidc.scopes-claim:}")
    private String scopesClaim;

    /**
     * The mapping every claim-reading component below shares - built here once so the role
     * converter, the tenant filter, the view-as synthetic token and the denial auditor can never
     * disagree about which claim is which.
     */
    private OidcClaimMapping claimMapping() {
        return new OidcClaimMapping(rolesClaimPath, tenantClaim, scopesClaim);
    }

    /**
     * {@code azp} validator (security audit 2026-09-18, tightened by SEC-056). With a non-empty
     * allowlist the token MUST carry {@code azp} and it MUST be on the list; a missing {@code azp}
     * is rejected. An EMPTY allowlist is the documented emergency lever and switches the check off
     * entirely.
     */
    static OAuth2TokenValidator<Jwt> authorizedPartyValidator(String allowedCsv) {
        java.util.Set<String> allowedParties = csvToSet(allowedCsv);
        return jwt -> {
            if (allowedParties.isEmpty()) {
                return OAuth2TokenValidatorResult.success();
            }
            String azp = jwt.getClaimAsString("azp");
            if (azp != null && allowedParties.contains(azp)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(
                            "invalid_token",
                            azp == null
                                    ? "The token has no azp claim"
                                    : "The token was not issued to a client this API accepts",
                            null));
        };
    }

    /** SEC-056 audience validator; an empty/blank {@code requiredCsv} is a no-op. */
    static OAuth2TokenValidator<Jwt> audienceValidator(String requiredCsv) {
        java.util.Set<String> required = csvToSet(requiredCsv);
        return jwt -> {
            if (required.isEmpty()) {
                return OAuth2TokenValidatorResult.success();
            }
            List<String> aud = jwt.getAudience();
            if (aud != null && aud.stream().anyMatch(required::contains)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error(
                            "invalid_token",
                            "The token audience is not one this API accepts",
                            null));
        };
    }

    private static java.util.Set<String> csvToSet(String csv) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (csv == null) {
            return out;
        }
        for (String candidate : csv.split(",")) {
            if (!candidate.isBlank()) {
                out.add(candidate.trim());
            }
        }
        return out;
    }

    /**
     * Mirrors the controller's own switch; when false, /v1/** stays a plain authenticated 404 and
     * vmcp_ keys get no special handling.
     */
    @Value("${app.openai-compat.enabled:false}")
    private boolean openAiCompatEnabled;

    /**
     * The Keycloak / generic-OIDC decoder. Absent only when {@code app.auth.mode=local}, where
     * {@link com.agenticai.core.security.localauth.LocalAuthSecurityConfig} supplies the decoder
     * for locally issued tokens; the default (property unset or {@code keycloak}) is unchanged.
     */
    @Bean
    @ConditionalOnProperty(name = "app.auth.mode", havingValue = "keycloak", matchIfMissing = true)
    public JwtDecoder jwtDecoder() {
        String issuerUri =
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", "");
        String jwkSetUri = environment.getProperty("app.keycloak.jwk-set-uri", "");
        if (issuerUri.isBlank() || jwkSetUri.isBlank()) {
            throw new IllegalStateException(
                    "app.auth.mode=keycloak needs spring.security.oauth2.resourceserver.jwt.issuer-uri"
                            + " (KEYCLOAK_ISSUER_URI) and app.keycloak.jwk-set-uri"
                            + " (KEYCLOAK_JWK_SET_URI); set them, or use app.auth.mode=local");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        java.util.LinkedHashSet<String> acceptedIssuers = new java.util.LinkedHashSet<>();
        acceptedIssuers.add(issuerUri);
        if (additionalIssuers != null && !additionalIssuers.isBlank()) {
            for (String candidate : additionalIssuers.split(",")) {
                String trimmed = candidate.trim();
                if (!trimmed.isEmpty()) {
                    acceptedIssuers.add(trimmed);
                }
            }
        }

        // Authorized-party check (security audit 2026-09-18). Signature + issuer
        // only prove "this realm minted it" - and EVERY client in the realm
        // mints with the same keys, including ones that have nothing to do with
        // this API (academy-webapp: open self-registration, its own session
        // store, its own XSS surface). A token stolen from or issued to such a
        // client used to be a fully valid core credential. Keycloak stamps the
        // client a token was issued to in `azp`; only first-party API clients
        // are accepted. SEC-056: a token with NO azp is now rejected too
        // (Keycloak always sets it, so a missing azp means a token this API
        // should not trust), and an optional audience check can be switched on.
        OAuth2TokenValidator<Jwt> authorizedPartyValidator =
                new DelegatingOAuth2TokenValidator<>(
                        authorizedPartyValidator(allowedAuthorizedParties),
                        audienceValidator(requiredAudience));

        if (acceptedIssuers.size() == 1) {
            // Default single-issuer deployment - unchanged from before.
            decoder.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(
                            JwtValidators.createDefaultWithIssuer(issuerUri),
                            authorizedPartyValidator));
        } else {
            // Multi-hostname deployment (see additionalIssuers javadoc): keep the
            // full default validation (timestamps etc.) but accept a token whose
            // `iss` matches ANY configured issuer, since they are all the same
            // realm with the same signing keys already covered by jwkSetUri.
            OAuth2TokenValidator<Jwt> issuerValidator =
                    jwt -> {
                        String iss = jwt.getClaimAsString("iss");
                        if (iss != null && acceptedIssuers.contains(iss)) {
                            return OAuth2TokenValidatorResult.success();
                        }
                        return OAuth2TokenValidatorResult.failure(
                                new OAuth2Error(
                                        "invalid_token",
                                        "The iss claim is not one of the accepted issuers "
                                                + acceptedIssuers,
                                        null));
                    };
            decoder.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(
                            JwtValidators.createDefault(),
                            issuerValidator,
                            authorizedPartyValidator));
        }
        return decoder;
    }

    /**
     * Dedicated chain for the Alertmanager webhook, ordered ahead of the main chain.
     *
     * <p><b>Why this exists.</b> {@code permitAll()} on {@code /webhook/**} in the main chain means
     * "no authentication required" - it does NOT mean "skip bearer-token processing". Spring's
     * {@code BearerTokenAuthenticationFilter} still runs whenever an {@code Authorization: Bearer
     * ...} header is present and, finding a shared secret rather than a signed JWT, fails the
     * request with 401 before any controller method is invoked. {@link
     * com.agenticai.core.platform.PlatformAlertWebhookController} expects exactly such a shared
     * secret, because Alertmanager's {@code http_config} supports only Basic/Bearer auth or mutual
     * TLS - it cannot send a custom header.
     *
     * <p>The result was an alert pipeline that could never deliver: correct credentials produced
     * 401 from the resource server, and the controller's own check only ever ran for requests that
     * had no credentials at all. Verified 2026-07-29 - a request with the right secret 401'd with
     * no controller log, while the same request with no header at all reached the controller and
     * logged its rejection.
     *
     * <p>This chain matches only the webhook path and deliberately omits {@code
     * oauth2ResourceServer}, so the bearer header passes through untouched and the controller
     * performs the constant-time comparison it was written to perform. The endpoint is NOT
     * unauthenticated: it fails closed on a blank or mismatched secret.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain alertWebhookFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/webhook/alerts/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Dedicated chain for core/mcpserver's inbound MCP server surface ({@code /mcp/**}, {@code
     * /api/dev/**}), ordered ahead of the main chain - the exact same fix as {@link
     * #alertWebhookFilterChain} above, for the exact same reason (see that bean's javadoc).
     *
     * <p>Confirmed live 2026-08-15: a real {@code vmcp_...} grant key sent as {@code Authorization:
     * Bearer vmcp_...} against {@code /mcp} got {@code 401 invalid_token, "Malformed token"} -
     * {@code BearerTokenAuthenticationFilter}, still present on the main chain via {@code
     * oauth2ResourceServer()}, tried to decode it as a JWT and failed before the request ever
     * reached {@code McpApiKeyAuthFilter} (registered as a plain servlet filter, ordered after
     * Spring Security - see {@code McpServerToolConfig#mcpApiKeyAuthFilterRegistration}). The main
     * chain's {@code permitAll()} on these paths (still present in this class's history) only
     * controls the AUTHORIZATION decision; {@code BearerTokenAuthenticationFilter} rejects a
     * malformed bearer token at the AUTHENTICATION step, before authorization is ever evaluated,
     * regardless of permitAll(). Omitting {@code oauth2ResourceServer()} here, same as {@code
     * alertWebhookFilterChain}, lets a {@code vmcp_...} key pass through untouched to {@code
     * McpApiKeyAuthFilter}, which is the only thing that actually authenticates this surface either
     * way.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain mcpServerFilterChain(HttpSecurity http) throws Exception {
        // The RFC 9728 well-known document is public by design (app.mcpserver.oauth.enabled).
        http.securityMatcher(
                        "/mcp/**",
                        "/api/dev/**",
                        "/.well-known/oauth-protected-resource/**",
                        "/.well-known/oauth-protected-resource")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            Optional<TenantStatusGuard> tenantStatusGuard,
            Optional<UserActivityRecorder> userActivityRecorder,
            Optional<PermissionAuthoritySource> permissionSource,
            List<AccessDeniedEventListener> accessDeniedListeners,
            ObjectProvider<DelegatedTokenAuthenticator> delegatedTokenAuthenticators,
            ObjectProvider<TenantApiKeyAuthenticator> tenantApiKeyAuthenticators,
            Optional<ImpersonationGrantResolver> impersonationGrantResolver)
            throws Exception {
        http.csrf(csrf -> csrf.disable()) // stateless bearer-token API, no browser form/cookie auth
                .cors(Customizer.withDefaults()) // delegate to WebConfig's CorsConfigurationSource
                // - see class javadoc
                // Spring Security already sends X-Content-Type-Options, X-Frame-Options
                // (DENY), and HSTS-on-HTTPS by default without any config here - the
                // three below are the ones it does NOT set unless told to. core is a
                // pure JSON API (the actual UI lives in web/web-admin), so a strict
                // default-src 'none' is correct: there's no first-party HTML/JS for
                // this origin to serve, only actuator/error pages that would otherwise
                // have no CSP at all.
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'none';"
                                                                        + " frame-ancestors 'none'"))
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                org.springframework.security.web
                                                                        .header.writers
                                                                        .ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                                        .permissionsPolicy(
                                                permissions ->
                                                        permissions.policy(
                                                                "camera=(), microphone=(),"
                                                                        + " geolocation=(),"
                                                                        + " interest-cohort=()")))
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Denied-attempt auditing (granular RBAC, 2026-09-23): an
                // authenticated request refused by the matchers below gets the
                // standard 403 JSON body AND an authz.denied audit-log entry via
                // the AccessDeniedEventListener SPI (empty when core/admin is
                // absent - the handler then behaves exactly like the default).
                .exceptionHandling(
                        exceptions ->
                                exceptions.accessDeniedHandler(
                                        new AuditingAccessDeniedHandler(
                                                accessDeniedListeners, tenantClaim)))
                .authorizeHttpRequests(
                        authorize -> {
                            // Rule classes in chain order; Spring Security applies the first match.
                            PublicSurfaceRules.apply(authorize);
                            AdminIntegrationRules.apply(authorize);
                            ToolChannelWorkflowRules.apply(authorize);
                            TenantFeatureRules.apply(authorize);
                            authorize
                                    // OpenAI-compatible surface (core/agents
                                    // OpenAiCompatController, only exists when
                                    // app.openai-compat.enabled=true). Accepts a platform JWT
                                    // (authenticated here as for any
                                    // /api/** call) OR a long-lived vmcp_ grant key, which an
                                    // OpenAI SDK sends as its api_key.
                                    // For the key case this rule only checks the bearer's
                                    // PREFIX; it does NOT validate it.
                                    // core/mcpserver's McpApiKeyAuthFilter (registered for
                                    // /v1/* by OpenAiCompatKeyAuthConfig)
                                    // does the real authentication, and the controller fails
                                    // closed with a 401 if it never ran.
                                    // The bearer resolver below keeps the JWT decoder away from
                                    // vmcp_ tokens on /v1/** (the
                                    // same "Malformed token" trap documented on
                                    // mcpServerFilterChain).
                                    .requestMatchers("/v1/**")
                                    .access(
                                            (authentication, context) ->
                                                    new AuthorizationDecision(
                                                            isAuthenticatedUser(
                                                                            authentication.get())
                                                                    || (openAiCompatEnabled
                                                                            && hasGrantKeyBearer(
                                                                                    context
                                                                                            .getRequest()))))
                                    .anyRequest()
                                    .authenticated();
                        })
                .oauth2ResourceServer(
                        oauth2 ->
                                oauth2.bearerTokenResolver(grantKeyAwareBearerTokenResolver())
                                        .authenticationEntryPoint(openAiAwareEntryPoint())
                                        .jwt(
                                                jwt ->
                                                        jwt.jwtAuthenticationConverter(
                                                                jwtAuthenticationConverter(
                                                                        permissionSource,
                                                                        claimMapping()))))
                // Delegation tokens (agent acting-for user, core/agents/trust):
                // authenticated BEFORE the OAuth2 bearer filter so a valid
                // Ed25519 delegation JWT isn't 401'd as a malformed Keycloak
                // token. The SPI is empty when core/agents isn't on the
                // classpath - the filter then passes everything through.
                .addFilterBefore(
                        new DelegationTokenAuthFilter(delegatedTokenAuthenticators),
                        BearerTokenAuthenticationFilter.class)
                // Tenant REST API keys (vdak_..., V483): authenticated before the
                // OAuth2 bearer filter so a key isn't 401'd as a malformed JWT.
                // The SPI is empty when core/tenancy's TenantApiKeyService isn't
                // on the classpath - the filter then passes everything through.
                .addFilterBefore(
                        new ApiKeyAuthFilter(tenantApiKeyAuthenticators),
                        BearerTokenAuthenticationFilter.class)
                // Read-only "view as user" (X-View-As): swaps the admin's validated
                // JWT for the target user's identity BEFORE TenantContextFilter and
                // URL authorization, so both evaluate the target. See ViewAsFilter.
                .addFilterAfter(
                        new ViewAsFilter(
                                impersonationGrantResolver,
                                jwtAuthenticationConverter(permissionSource, claimMapping()),
                                claimMapping()),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(
                        new TenantContextFilter(
                                tenantStatusGuard, userActivityRecorder, tenantClaim),
                        ViewAsFilter.class)
                // end-customer accounts: no console writes beyond chat/voice and their own
                // account (see the filter's javadoc for why this is not a matcher rule).
                .addFilterBefore(new EndCustomerWriteGuardFilter(), AuthorizationFilter.class);
        return http.build();
    }

    /** True for a real (non-anonymous) authenticated principal. */
    static boolean isAuthenticatedUser(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * True when the request carries {@code Authorization: Bearer vmcp_...} (a grant key, not a
     * JWT).
     */
    static boolean hasGrantKeyBearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return header != null
                && header.regionMatches(true, 0, "Bearer vmcp_", 0, "Bearer vmcp_".length());
    }

    /**
     * Default bearer extraction, except that on {@code /v1/**} (OpenAI-compatible surface, when
     * enabled) a {@code vmcp_} grant key is NOT handed to the JWT decoder, which would reject it as
     * "Malformed token" before the key filter ever ran. Every other path and token is unchanged.
     */
    private BearerTokenResolver grantKeyAwareBearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            if (openAiCompatEnabled
                    && request.getRequestURI().startsWith("/v1/")
                    && hasGrantKeyBearer(request)) {
                return null;
            }
            return delegate.resolve(request);
        };
    }

    /**
     * Default bearer 401, except that on {@code /v1/**} (flag on) an unauthenticated or
     * invalid-token call gets OpenAI's error envelope, which an OpenAI SDK parses into a typed
     * AuthenticationError. Everything else is the stock {@link
     * BearerTokenAuthenticationEntryPoint}.
     */
    private AuthenticationEntryPoint openAiAwareEntryPoint() {
        BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();
        return (request, response, ex) -> {
            if (openAiCompatEnabled && request.getRequestURI().startsWith("/v1/")) {
                response.setStatus(401);
                response.setHeader("WWW-Authenticate", "Bearer");
                response.setContentType("application/json");
                response.getWriter()
                        .write(
                                "{\"error\":{\"message\":\"Incorrect API key provided.\","
                                        + "\"type\":\"invalid_request_error\",\"param\":null,\"code\":\"invalid_api_key\"}}");
                return;
            }
            delegate.commence(request, response, ex);
            writeUnauthorizedProblem(request, response);
        };
    }

    /**
     * SE-80: the stock bearer entry point answers 401 with an empty body while every other error
     * carries a problem+json document. Adds one (fixed text: nothing about why the token failed
     * leaks; the {@code WWW-Authenticate} header the delegate set still carries the RFC 6750
     * detail). Skipped when the response is already committed or has a body.
     */
    static void writeUnauthorizedProblem(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response)
            throws java.io.IOException {
        if (response.isCommitted()) {
            return;
        }
        String path =
                request.getRequestURI() == null
                        ? ""
                        : request.getRequestURI().replace("\\", "").replace("\"", "");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter()
                .write(
                        "{\"type\":\"urn:vegaduta:problem:unauthorized\",\"title\":\"Unauthorized\","
                                + "\"status\":401,\"detail\":\"Authentication is required to access this resource.\","
                                + "\"instance\":\""
                                + path
                                + "\",\"timestamp\":\""
                                + java.time.Instant.now()
                                + "\","
                                + "\"error\":\"Authentication is required to access this resource.\"}");
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter(
            Optional<PermissionAuthoritySource> permissionSource, OidcClaimMapping claimMapping) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new GranularPermissionConverter(permissionSource, claimMapping));
        return converter;
    }
}
