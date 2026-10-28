package com.agenticai.core.security;

import com.agenticai.core.security.apikey.ApiKeyAuthenticationToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates {@link TenantContext} from the current request's authenticated JWT, for the duration of
 * that request only. Registered explicitly in {@link SecurityConfig} via {@code addFilterAfter(...,
 * BearerTokenAuthenticationFilter.class)} rather than as an auto-registered {@code @Component} - it
 * needs to run strictly after Spring Security has already validated the token and populated the
 * security context, and manual ordering in the filter chain is the reliable way to guarantee that
 * instead of hoping servlet filter registration order works out.
 *
 * <p><b>{@code tenantStatusGuard}, added 2026-07-11:</b> an {@link Optional} rather than a required
 * dependency, since {@code SecurityConfig} builds this filter with {@code new}, not Spring DI - see
 * {@link TenantStatusGuard}'s javadoc for the full incident this closes (an orphaned,
 * still-loginable Keycloak account for a tenant whose schema had already been dropped by a failed
 * signup, producing a raw 500 from every panel instead of one clear error). Empty whenever no
 * {@link TenantStatusGuard} bean exists (e.g. a narrower test context with no {@code core/tenancy}
 * on the classpath) - this filter then behaves exactly as it did before this check existed.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    private final Optional<TenantStatusGuard> tenantStatusGuard;
    private final Optional<UserActivityRecorder> activityRecorder;
    private final String tenantClaim;

    public TenantContextFilter(Optional<TenantStatusGuard> tenantStatusGuard) {
        this(tenantStatusGuard, Optional.empty());
    }

    /**
     * @param activityRecorder V475 last-seen tracking (2026-10-09) - optional for the same reason
     *     as {@code tenantStatusGuard}; empty means no activity is recorded and this filter behaves
     *     exactly as before.
     */
    public TenantContextFilter(
            Optional<TenantStatusGuard> tenantStatusGuard,
            Optional<UserActivityRecorder> activityRecorder) {
        this(tenantStatusGuard, activityRecorder, JwtClaimPaths.DEFAULT_TENANT_CLAIM);
    }

    /**
     * @param tenantClaim the JWT claim carrying the tenant/schema id - {@code
     *     app.security.oidc.tenant-claim} (see {@link OidcClaimMapping}); blank
     *     falls back to the Keycloak default {@code tenant_id}.
     */
    public TenantContextFilter(
            Optional<TenantStatusGuard> tenantStatusGuard,
            Optional<UserActivityRecorder> activityRecorder,
            String tenantClaim) {
        this.tenantStatusGuard = tenantStatusGuard;
        this.activityRecorder = activityRecorder;
        this.tenantClaim =
                (tenantClaim == null || tenantClaim.isBlank())
                        ? JwtClaimPaths.DEFAULT_TENANT_CLAIM
                        : tenantClaim;
    }

    /**
     * Run again on the ASYNC dispatch of a streamed response (SSE, {@code Flux}, {@code
     * StreamingResponseBody}, {@code DeferredResult}). {@link OncePerRequestFilter} skips it by
     * default, and the thread that finishes an async request, including the error handling that
     * records platform issues, then has no {@link TenantContext}: Hibernate resolves a schema that
     * does not exist and the write is lost. Spring Security restores the {@code Authentication} on
     * that dispatch from the request attribute where the authentication filter saved it: {@code
     * BearerTokenAuthenticationFilter} does that for JWTs and {@link DelegationTokenAuthFilter} for
     * delegation tokens, so both get a {@link TenantContext} here. {@code vmcp_} grant keys ({@code
     * McpApiKeyAuthFilter}, a separate servlet filter that also rate-limits and reads the body) are
     * not re-run on this dispatch and still get none.
     *
     * <p>The tenant-status check is TTL-cached ({@code app.tenant.status-cache-ttl}, 10 s), so a
     * stream longer than that does a cold lookup here. If the tenant has been stopped meanwhile,
     * the response is usually already committed; nothing can be written to it, so the dispatch is
     * ended without running the chain (see {@link #reject}).
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwtAuth) {
                Jwt jwt = jwtAuth.getToken();
                String tenantId = JwtClaimPaths.valueAsString(jwt, tenantClaim);
                boolean tenantMissing = tenantId == null || tenantId.isBlank();
                // Real incident, 2026-07-15: an authenticated JWT arrived with
                // NO tenant_id claim at all (the Keycloak user was missing its
                // tenant_id attribute - the exact gap scripts/keycloak/check-tenant-claim.ps1
                // exists to repair). The old code fell through here, stored a
                // TenantContext with a null tenantId, and every tenant-scoped
                // controller then threw its "SecurityConfig should have blocked
                // this" IllegalStateException as a raw 500 (seen simultaneously
                // from TenantSettingsController and UserAdminController).
                // Reject it here, once, with the same clear 403 shape the
                // tenantStatusGuard path below already uses.
                //
                // Deliberate exemption: platform admins (ROLE_platform-admin,
                // the web-admin console's /api/platform/** surface) are
                // tenant-less BY DESIGN - their token never carries tenant_id,
                // and they must keep passing through exactly as before
                // (except on the tenant-scoped /api/tenant/** surface, below).
                // Onboarding exemption: a social-login (e.g. Google) user is
                // authenticated but has no tenant yet - they must be able to
                // reach POST /api/onboarding to provision their organization.
                // That controller reads identity straight off the JWT (not
                // TenantContext), so a null-tenant context below is harmless
                // for it.
                boolean onboardingPath = request.getRequestURI().startsWith("/api/onboarding");
                if (tenantMissing && !hasPlatformAdminAuthority(jwtAuth) && !onboardingPath) {
                    log.warn(
                            "Rejected {} {} - authenticated JWT for subject '{}' carries no"
                                + " '{}' claim and no ROLE_platform-admin authority. The"
                                + " identity-provider user is missing the attribute mapped to the"
                                + " tenant claim (Keycloak: repair with"
                                + " scripts/keycloak/check-tenant-claim.ps1), or the token predates"
                                + " that attribute and the caller needs to log out and back in for"
                                + " a fresh one. Returning 403 instead of letting every"
                                + " tenant-scoped controller fail with a raw 500.",
                            request.getMethod(),
                            LogMasking.path(request.getRequestURI()),
                            jwt.getSubject(),
                            tenantClaim);
                    reject(response, MISSING_TENANT_CLAIM_BODY, request);
                    return;
                }
                // Kind stage, 2026-10-14: a platform-admin account with no
                // tenant (test@vegaduta.dev, which also holds tenant-admin)
                // opened the tenant console, whose shell polls
                // /api/tenant/settings and /api/tenant/usage-quota. The
                // exemption above let it through with a null-tenant context
                // and both controllers threw their "SecurityConfig should have
                // blocked this" IllegalStateException as a 500 every poll.
                // /api/tenant/** is tenant-scoped by definition (the public
                // brand logo is /api/share/branding/**, and "view as user"
                // has already swapped in the target's tenant-bearing token by
                // now), so a tenant-less platform admin gets a 403 there.
                if (tenantMissing && isTenantScopedPath(request.getRequestURI())) {
                    log.debug(
                            "Rejected {} {} - platform admin '{}' has no '{}' claim and this"
                                    + " route is tenant-scoped; returning 403.",
                            request.getMethod(),
                            LogMasking.path(request.getRequestURI()),
                            jwt.getSubject(),
                            tenantClaim);
                    reject(response, PLATFORM_ADMIN_NO_TENANT_BODY, request);
                    return;
                }
                Set<String> roles =
                        jwtAuth.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .collect(Collectors.toUnmodifiableSet());
                // TenantContext MUST be populated BEFORE the tenantStatusGuard
                // call below, not after it: the guard's isUsable does real JPA
                // lookups (control_plane.tenant, platform_kill_switch), and
                // every Hibernate query goes through TenantSchemaResolver,
                // which reads THIS thread-local. Running the guard first left
                // an authenticated JWT thread resolving with an empty
                // TenantContext - the exact authenticated_no_context
                // propagation-bug signature F-14's instrumentation exists to
                // catch (seen live in prod as the rate-limited "fell back to
                // public schema" WARN with nearest caller
                // TenantStatusGuardImpl). The guard's own queries are all
                // control_plane-schema-qualified and SET search_path is
                // schema-name-agnostic, so populating early changes nothing
                // about what the guard itself can see - a dropped-schema
                // tenant is still detected and still 403s here.
                TenantContext.set(TenantContext.of(tenantId, jwt.getSubject(), roles));
                // Direct feedback ("Store sufficient context for future
                // analysis, including... User/request ID, ...") - mirrors
                // RequestCorrelationFilter's requestId MDC entry so every log
                // line for an authenticated request also carries which
                // tenant/user it belongs to, without every log call site
                // needing to pass them explicitly.
                if (tenantId != null) {
                    MDC.put("tenantId", tenantId);
                }
                if (jwt.getSubject() != null) {
                    MDC.put("userId", jwt.getSubject());
                }
                if (!tenantMissing && isSystemSchemaClaim(tenantId)) {
                    log.warn(
                            "Rejected {} {} - tenant claim '{}' names the default/system schema.",
                            request.getMethod(),
                            LogMasking.path(request.getRequestURI()),
                            tenantId.replaceAll("[\\r\\n]", " "));
                    reject(response, TENANT_UNUSABLE_BODY, request);
                    return;
                }
                if (!tenantMissing
                        && tenantStatusGuard.isPresent()
                        && !tenantStatusGuard.get().isUsable(tenantId)) {
                    log.warn(
                            "Rejected {} {} - tenant '{}' resolved from the JWT is not usable"
                                + " (provisioning never completed, or was rolled back). Returning"
                                + " 403 instead of letting every downstream schema-scoped query"
                                + " fail with 'relation does not exist'.",
                            request.getMethod(),
                            LogMasking.path(request.getRequestURI()),
                            tenantId);
                    reject(response, TENANT_UNUSABLE_BODY, request);
                    return;
                }
                // A platform admin viewing as this user is not the user being active.
                if (!tenantMissing && !ImpersonationContext.isActive()) {
                    recordActivity(tenantId, jwt.getSubject());
                }
            } else if (authentication instanceof ApiKeyAuthenticationToken apiKeyAuth) {
                // Tenant REST API key (V483): tenant comes from the key row's
                // control-plane link; the subject is synthetic
                // ("apikey:<name>:<shortId>") so audit/ACL never confuses a
                // machine call with the human who minted the key. Roles carry
                // the key's PERM_* authority names - the same set shape the JWT
                // branch stores, so ResourceAccessService's domain-scoped
                // overrides work identically for keys. A key can hold no
                // ROLE_* authority, so nothing admin-tier opens.
                String tenantId = apiKeyAuth.getTenantId();
                String subject = apiKeyAuth.getPrincipal().toString();
                TenantContext.set(
                        TenantContext.of(tenantId, subject,
                                apiKeyAuth.getAuthorities().stream()
                                        .map(GrantedAuthority::getAuthority)
                                        .collect(Collectors.toUnmodifiableSet())));
                if (tenantId != null) {
                    MDC.put("tenantId", tenantId);
                }
                MDC.put("userId", subject);
                if (tenantId != null && isSystemSchemaClaim(tenantId)) {
                    log.warn(
                            "Rejected {} {} - API key '{}' resolved the default/system schema.",
                            request.getMethod(),
                            LogMasking.path(request.getRequestURI()),
                            subject);
                    reject(response, TENANT_UNUSABLE_BODY, request);
                    return;
                }
                if (tenantStatusGuard.isPresent()
                        && !tenantStatusGuard.get().isUsable(tenantId)) {
                    log.warn(
                            "Rejected {} {} - API key '{}' resolved tenant '{}' which is not usable.",
                            request.getMethod(),
                            LogMasking.path(request.getRequestURI()),
                            subject,
                            tenantId);
                    reject(response, TENANT_UNUSABLE_BODY, request);
                    return;
                }
                // Deliberately no recordActivity: a machine key is not a user
                // presence signal, and a poller would poison last-seen metrics.
            } else if (authentication instanceof DelegationAuthenticationToken delegated) {
                // Delegation-token caller (core/agents trust surface): the
                // tenant comes from the token's tenant_id claim; the subject
                // is the DELEGATING user so per-user ACLs evaluate as them;
                // the jti rides externalTriggerId so audit rows can tie the
                // request back to the exact token that authorized it.
                TenantContext.set(
                        TenantContext.of(
                                delegated.getTenantId(),
                                delegated.getPrincipal().toString(),
                                Set.of(),
                                "delegation:" + delegated.getJti()));
                if (delegated.getTenantId() != null) {
                    MDC.put("tenantId", delegated.getTenantId());
                }
                MDC.put("userId", delegated.getPrincipal().toString());
                // A delegation token's tenant claim gets the same checks as a JWT's: it must not
                // name the default/system schema, and the tenant must be usable (active, not
                // stopped, schema migrated, canonical schema name).
                if (isSystemSchemaClaim(delegated.getTenantId())) {
                    log.warn(
                            "Rejected {} {} - delegation token {} carries a default/system"
                                    + " schema tenant claim.",
                            request.getMethod(),
                            LogMasking.path(request.getRequestURI()),
                            delegated.getJti());
                    reject(response, TENANT_UNUSABLE_BODY, request);
                    return;
                }
                if (delegated.getTenantId() != null
                        && !delegated.getTenantId().isBlank()
                        && tenantStatusGuard.isPresent()
                        && !tenantStatusGuard.get().isUsable(delegated.getTenantId())) {
                    log.warn(
                            "Rejected {} {} - delegation token {} resolved tenant '{}' which is"
                                    + " not usable.",
                            request.getMethod(),
                            LogMasking.path(request.getRequestURI()),
                            delegated.getJti(),
                            delegated.getTenantId().replaceAll("[\\r\\n]", " "));
                    reject(response, TENANT_UNUSABLE_BODY, request);
                    return;
                }
            } else if (authentication != null
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                // (Anonymous callers of permitAll endpoints - the web app's
                // /api/system/status/live poll, health, public pages - are the
                // NORMAL non-JWT case. They used to hit this branch and log a
                // multi-argument WARN on every single request.)
                // Real incident, not hypothetical: UserAdminController threw
                // "No TenantContext for an authenticated /api/admin/users
                // request - SecurityConfig should have blocked this" even
                // though the caller HAD passed the earlier hasAuthority(
                // "ROLE_tenant-admin") check on the same request - meaning
                // SOME Authentication was present and authorized, just not a
                // JwtAuthenticationToken by the time this filter ran. This
                // log line exists to catch that discrepancy directly instead
                // of guessing blind next time: it names the actual runtime
                // class Spring Security handed this filter, which
                // instanceof alone doesn't surface anywhere else.
                log.warn(
                        "TenantContextFilter saw a non-JWT Authentication ({}) for {} {} -"
                            + " TenantContext will be empty for this request, and any tenant-scoped"
                            + " code downstream (UserAdminController, Hibernate's schema resolver,"
                            + " etc.) will fail. Authorities: {}",
                        authentication.getClass().getName(),
                        request.getMethod(),
                        LogMasking.path(request.getRequestURI()),
                        authentication.getAuthorities());
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            // Same pooled-thread hygiene as TenantContext: a chat turn pins
            // its resolved agent version here (AgentVersionPin's javadoc) and
            // a stale pin must never leak into the next request this thread
            // happens to serve.
            AgentVersionPin.clear();
            MDC.remove("tenantId");
            MDC.remove("userId");
        }
    }

    /**
     * Last-seen tracking must never fail or slow a request: the recorder is itself throttled and
     * asynchronous, and anything it still throws is swallowed here (logged at DEBUG - it would
     * otherwise repeat per request).
     */
    private void recordActivity(String tenantId, String subject) {
        if (activityRecorder.isEmpty() || subject == null) {
            return;
        }
        try {
            activityRecorder.get().recordSeen(tenantId, subject);
        } catch (RuntimeException e) {
            log.debug("Last-seen recording failed for subject '{}' - ignored", subject, e);
        }
    }

    /** See {@link TenantSchemaClaims#isSystemSchemaClaim}; kept for the filter's own tests. */
    static boolean isSystemSchemaClaim(String tenantId) {
        return TenantSchemaClaims.isSystemSchemaClaim(tenantId);
    }

    private static boolean hasPlatformAdminAuthority(JwtAuthenticationToken jwtAuth) {
        return jwtAuth.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_platform-admin".equals(authority.getAuthority()));
    }

    private static final String MISSING_TENANT_CLAIM_BODY =
            "Your account is not linked to an organization. Please sign out and back in; if this"
                    + " persists, contact support.";

    /** {@code /api/tenant} and everything under it; never {@code /api/tenants...}. */
    static boolean isTenantScopedPath(String uri) {
        return uri != null && (uri.equals("/api/tenant") || uri.startsWith("/api/tenant/"));
    }

    private static final String PLATFORM_ADMIN_NO_TENANT_BODY =
            "This platform-admin account is not linked to an organization, so it has no"
                    + " organization settings or usage. Use the platform-admin console, or sign in"
                    + " with an organization account.";

    private static final String TENANT_UNUSABLE_BODY =
            "Your organization's account setup did not complete successfully. Please contact"
                    + " support or sign up again.";

    /**
     * Answers 403. Deliberately hand-built (this filter runs before {@code DispatcherServlet}, so
     * {@code GlobalExceptionHandler}'s {@code @RestControllerAdvice} machinery is never reached)
     * but kept in the same {@code {timestamp, status, error}} shape that handler already uses for
     * every other JSON error body, so the web console's existing error-toast handling renders this
     * identically to any other failure.
     *
     * <p>A committed response (an SSE body already streaming when the tenant was stopped, seen on
     * the ASYNC dispatch) can no longer take a status or a body, and {@code getWriter()} on a
     * response whose output stream was used throws {@code IllegalStateException} from inside the
     * filter. It is left untouched and the chain is not run, so no more tenant-scoped code executes
     * for the stopped tenant.
     */
    private static void reject(
            HttpServletResponse response, String error, HttpServletRequest request)
            throws IOException {
        if (response.isCommitted()) {
            log.warn(
                    "Not writing the 403 for {} {}: the response is already committed (a stream"
                            + " in flight when the tenant stopped being usable); ending the"
                            + " dispatch without a TenantContext.",
                    request.getMethod(),
                    LogMasking.path(request.getRequestURI()));
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter()
                .write(
                        "{\"timestamp\":\""
                                + java.time.Instant.now()
                                + "\",\"status\":403,\"error\":\""
                                + error
                                + "\"}");
    }
}
