package com.agenticai.core.security;

import com.agenticai.core.security.ImpersonationGrantResolver.ViewAsSession;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Read-only "view as user" for platform admins. Runs right after the bearer token is validated and
 * BEFORE {@link TenantContextFilter} and URL authorization, so everything downstream - URL rules,
 * {@code @PreAuthorize}, {@code Principal#getName}, {@code @AuthenticationPrincipal Jwt}, {@link
 * TenantContext}, the schema resolver, {@link GranularPermissionConverter}'s per-user permissions -
 * sees the TARGET user, not the admin.
 *
 * <p>A request without {@code X-View-As} passes through untouched. With it:
 *
 * <ol>
 *   <li>the caller must hold a validated JWT with {@code ROLE_platform-admin}, else 401 {@code
 *       view_as_invalid};
 *   <li>the grant must resolve (hash lookup) to a session created by the SAME admin (constant-time
 *       subject compare), else 401 {@code view_as_invalid};
 *   <li>the session must be neither ended nor expired, else 410 {@code view_as_expired};
 *   <li>the method must be GET/HEAD/OPTIONS, the path must not be on the side-effect denylist, and
 *       it must not be a websocket upgrade, else 403 {@code impersonation_read_only}.
 * </ol>
 *
 * A rejected grant never falls back to the admin's own identity. The admin's token is restored
 * after the request (stateless chain, so nothing persists). Each honoured request is logged at INFO
 * with session id, method and path - never the grant, never bodies.
 */
public class ViewAsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ViewAsFilter.class);

    public static final String HEADER = "X-View-As";
    static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_platform-admin";
    static final String ERROR_INVALID = "view_as_invalid";
    static final String ERROR_EXPIRED = "view_as_expired";
    private static final int MAX_GRANT_LENGTH = 256;
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * GETs with side effects (OAuth starts/callbacks, connector links, exports, unsubscribe / magic
     * / accept / confirm links, secret material) plus whole surfaces a target user never needs
     * while being viewed. Matched against the lower-cased request URI.
     */
    static final List<Pattern> DENYLIST =
            List.of(
                    Pattern.compile("(^|/)oauth2?(/|$)"),
                    Pattern.compile("/callback(/|$)"),
                    Pattern.compile("authorize-url"),
                    Pattern.compile("/authorize(/|$)"),
                    Pattern.compile("/connect(/|$)"),
                    Pattern.compile("/disconnect(/|$)"),
                    Pattern.compile("unsubscribe"),
                    Pattern.compile("magic"),
                    Pattern.compile("/export"),
                    Pattern.compile("/accept(/|$)"),
                    Pattern.compile("/confirm(/|$)"),
                    Pattern.compile("/verify-email"),
                    Pattern.compile("/logout(/|$)"),
                    Pattern.compile("/reveal(/|$)"),
                    Pattern.compile("/secrets?/.+/value"),
                    Pattern.compile("scim-tokens"),
                    Pattern.compile("/api-keys?(/|$)"),
                    Pattern.compile("^/api/onboarding"),
                    Pattern.compile("^/api/platform(/|$)"),
                    Pattern.compile("^/api/budget/top-up"),
                    Pattern.compile("^/ws(/|$)"),
                    Pattern.compile("/websocket(/|$)"));

    private final Optional<ImpersonationGrantResolver> resolver;
    private final Converter<Jwt, ? extends AbstractAuthenticationToken> authenticationConverter;
    private final Clock clock;
    private final OidcClaimMapping claimMapping;

    public ViewAsFilter(
            Optional<ImpersonationGrantResolver> resolver,
            Converter<Jwt, ? extends AbstractAuthenticationToken> authenticationConverter) {
        this(resolver, authenticationConverter, Clock.systemUTC());
    }

    public ViewAsFilter(
            Optional<ImpersonationGrantResolver> resolver,
            Converter<Jwt, ? extends AbstractAuthenticationToken> authenticationConverter,
            OidcClaimMapping claimMapping) {
        this(resolver, authenticationConverter, Clock.systemUTC(), claimMapping);
    }

    ViewAsFilter(
            Optional<ImpersonationGrantResolver> resolver,
            Converter<Jwt, ? extends AbstractAuthenticationToken> authenticationConverter,
            Clock clock) {
        this(resolver, authenticationConverter, clock, OidcClaimMapping.keycloakDefaults());
    }

    ViewAsFilter(
            Optional<ImpersonationGrantResolver> resolver,
            Converter<Jwt, ? extends AbstractAuthenticationToken> authenticationConverter,
            Clock clock,
            OidcClaimMapping claimMapping) {
        this.resolver = resolver;
        this.authenticationConverter = authenticationConverter;
        this.clock = clock;
        this.claimMapping = claimMapping == null ? OidcClaimMapping.keycloakDefaults() : claimMapping;
    }

    /**
     * Async re-dispatches (SSE/streaming completion) must keep running as the target, not the
     * admin.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String grant = request.getHeader(HEADER);
        if (grant == null) {
            chain.doFilter(request, response);
            return;
        }
        boolean asyncRedispatch = request.getDispatcherType() == DispatcherType.ASYNC;
        grant = grant.trim();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Public / unauthenticated surfaces (status poll, health, anonymous callers) never take
        // a view-as identity and never fail because the web client attached the header.
        if (isPublicPath(pathOf(request))
                || authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            chain.doFilter(request, response);
            return;
        }
        if (!(authentication instanceof JwtAuthenticationToken adminAuth)
                || !isPlatformAdmin(adminAuth)) {
            log.warn(
                    "view-as rejected: {} {} carries X-View-As without a platform-admin token",
                    request.getMethod(),
                    request.getRequestURI());
            write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    ERROR_INVALID,
                    "View-as requires a platform-admin sign-in.");
            return;
        }
        if (grant.isEmpty() || grant.length() > MAX_GRANT_LENGTH || resolver.isEmpty()) {
            write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    ERROR_INVALID,
                    "Unknown view-as grant.");
            return;
        }
        Optional<ViewAsSession> found;
        try {
            found = resolver.get().resolve(grant);
        } catch (RuntimeException e) {
            log.error(
                    "view-as rejected: session lookup failed for {} {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    e);
            write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    ERROR_INVALID,
                    "View-as session could not be verified.");
            return;
        }
        String adminSubject = adminAuth.getToken().getSubject();
        if (found.isEmpty()) {
            log.warn(
                    "view-as rejected: unknown grant presented by admin {} on {} {}",
                    adminSubject,
                    request.getMethod(),
                    request.getRequestURI());
            write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    ERROR_INVALID,
                    "Unknown view-as grant.");
            return;
        }
        ViewAsSession session = found.get();
        if (!constantTimeEquals(session.adminKeycloakId(), adminSubject)) {
            log.warn(
                    "view-as rejected: session {} belongs to a different admin than {}",
                    session.id(),
                    adminSubject);
            write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    ERROR_INVALID,
                    "This view-as session was not started by you.");
            return;
        }
        if (!session.isUsableAt(clock.instant())) {
            write(
                    response,
                    410,
                    ERROR_EXPIRED,
                    "This view-as session has ended. Start a new one to continue.");
            return;
        }
        String path = pathOf(request);
        if (!READ_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))
                || isDenied(path)
                || isWebSocketUpgrade(request)) {
            log.info(
                    "view-as blocked session={} method={} path={}",
                    session.id(),
                    request.getMethod(),
                    path);
            write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    ImpersonationReadOnlyException.ERROR_CODE,
                    "View-as is read-only: this action is not available while viewing as a user.");
            return;
        }

        AbstractAuthenticationToken targetAuth =
                authenticationConverter.convert(
                        targetJwt(adminAuth.getToken(), session, claimMapping));
        if (targetAuth == null) {
            write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    ERROR_INVALID,
                    "View-as session could not be verified.");
            return;
        }
        SecurityContext original = SecurityContextHolder.getContext();
        SecurityContext viewAs = SecurityContextHolder.createEmptyContext();
        viewAs.setAuthentication(targetAuth);
        if (!asyncRedispatch) {
            log.info(
                    "view-as request session={} method={} path={}",
                    session.id(),
                    request.getMethod(),
                    path);
            try {
                resolver.get().recordRequest(session.id());
            } catch (RuntimeException e) {
                log.debug("view-as request counter failed for session {}", session.id(), e);
            }
        }
        response.setHeader("Cache-Control", "no-store");
        try {
            SecurityContextHolder.setContext(viewAs);
            ImpersonationContext.set(
                    new ImpersonationContext.Session(
                            session.id(),
                            adminSubject,
                            session.adminEmail(),
                            session.tenantSchema(),
                            session.targetKeycloakId()));
            MDC.put("viewAsSession", session.id().toString());
            chain.doFilter(request, response);
        } finally {
            ImpersonationContext.clear();
            MDC.remove("viewAsSession");
            SecurityContextHolder.setContext(original);
        }
    }

    /**
     * The target's identity as a JWT: subject, tenant claim and realm roles of the target; validity
     * window never longer than the session or the admin's own token. {@code platform-admin} can
     * never appear (refused at session start, stripped here again). {@code act} records who is
     * really acting.
     */
    static Jwt targetJwt(Jwt adminJwt, ViewAsSession session) {
        return targetJwt(adminJwt, session, OidcClaimMapping.keycloakDefaults());
    }

    /**
     * Same as {@link #targetJwt(Jwt, ViewAsSession)} but writes the tenant and
     * roles claims under the configured {@link OidcClaimMapping} locations
     * (Keycloak's {@code tenant_id}/{@code realm_access.roles} by default), so
     * the synthetic token is readable by the same converters the real token
     * went through - see {@link JwtClaimPaths#putNested} for how a configured
     * dot-path or namespaced claim is shaped.
     */
    static Jwt targetJwt(Jwt adminJwt, ViewAsSession session, OidcClaimMapping claimMapping) {
        if (claimMapping == null) {
            claimMapping = OidcClaimMapping.keycloakDefaults();
        }
        List<String> roles =
                session.targetRoles() == null
                        ? List.of()
                        : session.targetRoles().stream()
                                .filter(
                                        r ->
                                                r != null
                                                        && !r.isBlank()
                                                        && !"platform-admin".equals(r))
                                .toList();
        Instant issuedAt = adminJwt.getIssuedAt() == null ? Instant.now() : adminJwt.getIssuedAt();
        Instant expiresAt =
                adminJwt.getExpiresAt() == null
                                || adminJwt.getExpiresAt().isAfter(session.expiresAt())
                        ? session.expiresAt()
                        : adminJwt.getExpiresAt();
        if (!expiresAt.isAfter(issuedAt)) {
            issuedAt = expiresAt.minusSeconds(1);
        }
        Map<String, Object> act = new LinkedHashMap<>();
        act.put("sub", adminJwt.getSubject());
        if (session.adminEmail() != null) {
            act.put("email", session.adminEmail());
        }
        Map<String, Object> mappedClaims = new LinkedHashMap<>();
        JwtClaimPaths.putNested(mappedClaims, claimMapping.tenantClaim(), session.tenantSchema());
        JwtClaimPaths.putNested(mappedClaims, claimMapping.rolesClaimPath(), roles);
        mappedClaims.put("act", act);
        mappedClaims.put("view_as_session", session.id().toString());
        Jwt.Builder builder =
                Jwt.withTokenValue(adminJwt.getTokenValue())
                        .headers(h -> h.putAll(adminJwt.getHeaders()))
                        .subject(session.targetKeycloakId())
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .claims(claims -> claims.putAll(mappedClaims));
        if (adminJwt.getIssuer() != null) {
            builder.issuer(adminJwt.getIssuer().toString());
        }
        if (session.targetEmail() != null) {
            builder.claim("email", session.targetEmail())
                    .claim("preferred_username", session.targetEmail());
        }
        return builder.build();
    }

    /** permitAll surfaces where the header is ignored outright (see doFilterInternal). */
    static final List<Pattern> PUBLIC_PATHS =
            List.of(
                    Pattern.compile("^/api/public(/|$)"),
                    Pattern.compile("^/api/system/status(/|$)"),
                    Pattern.compile("^/actuator(/|$)"),
                    Pattern.compile("^/api/health(/|$)"),
                    Pattern.compile("^/api/share(/|$)"),
                    Pattern.compile("^/api/csp-report(/|$)"),
                    Pattern.compile("^/api/auth/social-providers(/|$)"),
                    Pattern.compile("^/api/signup(/|$)"),
                    Pattern.compile("^/api/demo(/|$)"),
                    Pattern.compile("^/api/directory(/|$)"),
                    Pattern.compile("^/api/templates/gallery(/|$)"),
                    Pattern.compile("^/api/plugins/(catalog|artifacts)(/|$)"),
                    Pattern.compile("^/api/billing/webhook(/|$)"),
                    Pattern.compile("^/api/commerce/feed(/|$)"));

    static boolean isPublicPath(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (Pattern pattern : PUBLIC_PATHS) {
            if (pattern.matcher(p).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean isDenied(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        for (Pattern pattern : DENYLIST) {
            if (pattern.matcher(p).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWebSocketUpgrade(HttpServletRequest request) {
        String upgrade = request.getHeader("Upgrade");
        return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket");
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            uri = uri.substring(context.length());
        }
        return uri;
    }

    private static boolean isPlatformAdmin(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .anyMatch(a -> PLATFORM_ADMIN_AUTHORITY.equals(a.getAuthority()));
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hand-built (runs before DispatcherServlet), same {@code {timestamp,status,error,message}}
     * shape as the API's errors.
     */
    private static void write(
            HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", "no-store");
        response.getWriter()
                .write(
                        "{\"timestamp\":\""
                                + Instant.now()
                                + "\",\"status\":"
                                + status
                                + ",\"error\":\""
                                + error
                                + "\",\"message\":\""
                                + message.replace("\"", "'")
                                + "\"}");
    }
}
