package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * {@link TenantContextFilter} is what populates {@link TenantContext} for every authenticated
 * request - every schema-per-tenant query, every {@code @PreAuthorize}/{@code hasRole} check
 * downstream, and the {@link TenantStatusGuard} 403-rejection path that closed a real
 * orphaned-account incident (see this class's own javadoc) all depend on this filter running
 * correctly. Had zero test coverage before this suite.
 *
 * <p>Calls {@code doFilterInternal} directly rather than the inherited {@code doFilter} - both are
 * accessible here since this test lives in the same package, and going through the real {@code
 * OncePerRequestFilter} dispatch machinery (which tracks an "already filtered" request attribute)
 * would add mocking noise with no bearing on this class's own logic.
 *
 * <p>Uses a real {@link JwtAuthenticationToken}/{@link Jwt} rather than mocking them - simpler and
 * closer to what Spring Security actually hands this filter at runtime than stubbing every method
 * Jwt happens to expose.
 */
class TenantContextFilterTest {

    @AfterEach
    void clearSecurityAndTenantContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static Jwt jwt(String subject, String tenantId) {
        Jwt.Builder builder =
                Jwt.withTokenValue("token-value")
                        .header("alg", "none")
                        .subject(subject)
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300));
        if (tenantId != null) {
            builder.claim("tenant_id", tenantId);
        }
        return builder.build();
    }

    private static HttpServletRequest requestMock() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/agents");
        return request;
    }

    @Test
    void validJwtWithTenantIdPopulatesTenantContextForDownstreamChain() throws Exception {
        Jwt jwt = jwt("user-1", "tenant_b");
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_tenant-admin"));
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, authorities));

        TenantContextFilter filter = new TenantContextFilter(Optional.empty());
        FilterChain chain = mock(FilterChain.class);
        AtomicReference<TenantContext> capturedDuringChain = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            capturedDuringChain.set(TenantContext.current().orElse(null));
                            return null;
                        })
                .when(chain)
                .doFilter(any(), any());

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(any(), any());
        TenantContext duringChain = capturedDuringChain.get();
        assertEquals("tenant_b", duringChain.getTenantId());
        assertEquals("user-1", duringChain.getSubject());
        assertTrue(duringChain.hasRole("ROLE_tenant-admin"));

        // The filter's own finally block must clear TenantContext once the
        // chain returns - a request-scoped value must never leak onto
        // whatever the next request handled on this (pooled) thread sees.
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void noAuthenticationPassesThroughWithEmptyTenantContext() throws Exception {
        // SecurityContextHolder's context has no Authentication at all -
        // an anonymous/public endpoint. Must not throw, must still invoke
        // the chain.
        TenantContextFilter filter = new TenantContextFilter(Optional.empty());
        FilterChain chain = mock(FilterChain.class);
        AtomicReference<Boolean> hadContextDuringChain = new AtomicReference<>();
        doAnswer(
                        invocation -> {
                            hadContextDuringChain.set(TenantContext.current().isPresent());
                            return null;
                        })
                .when(chain)
                .doFilter(any(), any());

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertEquals(Boolean.FALSE, hadContextDuringChain.get());
    }

    @Test
    void nonJwtAuthenticationPassesThroughWithoutPopulatingTenantContext() throws Exception {
        // Real incident this class's javadoc documents: SOME Authentication
        // was present and already authorized by SecurityConfig, but not a
        // JwtAuthenticationToken by the time this filter ran. Must log and
        // continue, not throw - the downstream 401/403 (if any) should come
        // from whatever code actually needs TenantContext and doesn't find
        // one, not from this filter itself.
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken("some-user", "n/a", List.of()));

        TenantContextFilter filter = new TenantContextFilter(Optional.empty());
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void jwtTenantClaimNamingThePublicSchemaIsRejectedBeforeTheGuard() throws Exception {
        for (String claim : new String[] {"public", " PUBLIC ", "control_plane"}) {
            SecurityContextHolder.getContext()
                    .setAuthentication(new JwtAuthenticationToken(jwt("user-1", claim), List.of()));
            TenantStatusGuard guard = mock(TenantStatusGuard.class);
            when(guard.isUsable(any())).thenReturn(true);
            TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
            FilterChain chain = mock(FilterChain.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(response.getWriter()).thenReturn(mock(PrintWriter.class));

            filter.doFilterInternal(requestMock(), response, chain);

            verify(chain, never()).doFilter(any(), any());
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    private static DelegationAuthenticationToken delegation(String tenantId) {
        return new DelegationAuthenticationToken(
                tenantId, "user-1", java.util.UUID.randomUUID(), "aud", List.of("agents:read"), "jti-1");
    }

    @Test
    void delegationTokenNamingThePublicOrASystemSchemaIsRejected() throws Exception {
        for (String claim : new String[] {"public", " PUBLIC ", "Public", "control_plane", "pg_catalog", "ag_catalog"}) {
            SecurityContextHolder.getContext().setAuthentication(delegation(claim));
            TenantStatusGuard guard = mock(TenantStatusGuard.class);
            when(guard.isUsable(any())).thenReturn(true);
            TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
            FilterChain chain = mock(FilterChain.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(response.getWriter()).thenReturn(mock(PrintWriter.class));

            filter.doFilterInternal(requestMock(), response, chain);

            verify(chain, never()).doFilter(any(), any());
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
            assertTrue(TenantContext.current().isEmpty(), claim);
        }
    }

    @Test
    void delegationTokenForAnUnusableTenantIsRejectedByTheGuard() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(delegation("tenant_stopped"));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        when(guard.isUsable("tenant_stopped")).thenReturn(false);
        TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(mock(PrintWriter.class));

        filter.doFilterInternal(requestMock(), response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void delegationTokenForAUsableTenantPopulatesTheContext() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(delegation("tenant_ok"));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        when(guard.isUsable("tenant_ok")).thenReturn(true);
        TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
        AtomicReference<TenantContext> seen = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seen.set(TenantContext.current().orElse(null));
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        assertEquals("tenant_ok", seen.get().getTenantId());
        assertEquals("delegation:jti-1", seen.get().getExternalTriggerId());
        assertFalse(seen.get().isPlatformDefaultSchema());
    }

    @Test
    void onlyTheNamedFactoriesProduceAPlatformDefaultContext() {
        assertFalse(TenantContext.of("public", "s", java.util.Set.of()).isPlatformDefaultSchema());
        assertFalse(TenantContext.of("public", "s", java.util.Set.of(), "x").isPlatformDefaultSchema());
        assertTrue(TenantContext.ofPlatformDefaultSchema("s", java.util.Set.of()).isPlatformDefaultSchema());
        assertTrue(TenantContext.ofWebhookSchema("public", "s", java.util.Set.of()).isPlatformDefaultSchema());
        assertFalse(TenantContext.ofWebhookSchema("t_abc", "s", java.util.Set.of()).isPlatformDefaultSchema());
    }

    @Test
    void guardAbsentAllowsRequestThroughRegardlessOfTenant() throws Exception {
        // Optional<TenantStatusGuard> empty - the narrower test-context /
        // no-tenancy-module-on-classpath case this class's javadoc calls
        // out. Must behave exactly as if no guard existed.
        Jwt jwt = jwt("user-1", "tenant_gone");
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        TenantContextFilter filter = new TenantContextFilter(Optional.empty());
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void usableTenantPerGuardAllowsRequestThrough() throws Exception {
        Jwt jwt = jwt("user-1", "tenant_b");
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        when(guard.isUsable("tenant_b")).thenReturn(true);

        TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(any(), any());
        verify(guard).isUsable("tenant_b");
    }

    @Test
    void guardRunsWithTenantContextAlreadyPopulated() throws Exception {
        // Regression for the F-14 propagation bug seen live in prod: the
        // guard used to be invoked BEFORE TenantContext.set, so every JPA
        // query inside TenantStatusGuardImpl hit TenantSchemaResolver with
        // an empty context on a thread still carrying a tenant JWT - the
        // authenticated_no_context fallback kind (rate-limited WARN, meter
        // tenant.context.public.fallback). The guard must now observe the
        // same populated context the downstream chain gets.
        Jwt jwt = jwt("user-1", "tenant_b");
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_tenant-admin"));
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        AtomicReference<TenantContext> capturedDuringGuard = new AtomicReference<>();
        when(guard.isUsable("tenant_b"))
                .thenAnswer(
                        invocation -> {
                            capturedDuringGuard.set(TenantContext.current().orElse(null));
                            return true;
                        });

        TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(any(), any());
        TenantContext duringGuard = capturedDuringGuard.get();
        assertEquals("tenant_b", duringGuard.getTenantId());
        assertEquals("user-1", duringGuard.getSubject());
        assertTrue(duringGuard.hasRole("ROLE_tenant-admin"));
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void unusableTenantPerGuardRejectsWith403AndNeverInvokesChain() throws Exception {
        // The actual incident this guard closes: an orphaned Keycloak
        // account for a tenant whose schema was already dropped. Must
        // reject BEFORE the chain runs (never let it reach a controller
        // that would otherwise throw a raw "relation does not exist" 500),
        // and must never populate TenantContext for this request either.
        Jwt jwt = jwt("orphaned-user", "tenant_dropped");
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        when(guard.isUsable("tenant_dropped")).thenReturn(false);

        TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);

        filter.doFilterInternal(requestMock(), response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(response).setContentType("application/json");
        verify(writer).write(contains("\"status\":403"));
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void blankTenantIdWithoutPlatformAdminRejectsWith403AndNeverReachesGuardOrChain()
            throws Exception {
        // Real incident, 2026-07-15: a Keycloak user missing its tenant_id
        // attribute produced an authenticated JWT with no tenant claim; the
        // filter used to pass it through with a null-tenant TenantContext and
        // every tenant-scoped controller 500ed ("SecurityConfig should have
        // blocked this"). The filter now rejects tenant-less non-platform-admin
        // JWTs up front with the same 403 shape as the tenantStatusGuard path,
        // and still never calls the guard with a blank id (which
        // TenantStatusGuard's own javadoc says it never expects).
        Jwt jwt = jwt("user-1", "");
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);

        TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);

        filter.doFilterInternal(requestMock(), response, chain);

        verify(guard, never()).isUsable(anyString());
        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(response).setContentType("application/json");
        verify(writer).write(contains("\"status\":403"));
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void missingTenantIdClaimWithoutPlatformAdminRejectsWith403() throws Exception {
        // Same incident as above, with the claim absent entirely rather than
        // blank - both shapes must be treated identically.
        Jwt jwt = jwt("user-with-broken-keycloak-attributes", null);
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);

        TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);

        filter.doFilterInternal(requestMock(), response, chain);

        verify(guard, never()).isUsable(anyString());
        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(writer).write(contains("\"status\":403"));
    }

    @Test
    void platformAdminWithoutTenantIdClaimStillPassesThroughAndSkipsGuard() throws Exception {
        // Platform admins (web-admin console, /api/platform/**) are
        // tenant-less BY DESIGN - their token never carries tenant_id and the
        // missing-claim rejection above must not apply to them. Also locks in
        // that the guard is never called with a blank/null id.
        Jwt jwt = jwt("platform-admin-user", null);
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_platform-admin"));
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);

        TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(guard, never()).isUsable(anyString());
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void platformAdminWithoutTenantIdIsRejectedWith403OnTenantScopedRoutes() throws Exception {
        // Kind stage, 2026-10-14: a tenant-less platform admin (also holding
        // tenant-admin) on the tenant console polled /api/tenant/settings and
        // /api/tenant/usage-quota, and both controllers threw "SecurityConfig
        // should have blocked this" as a 500.
        for (String uri : List.of("/api/tenant/settings", "/api/tenant/usage-quota")) {
            Jwt jwt = jwt("platform-admin-user", null);
            List<GrantedAuthority> authorities =
                    List.of(
                            new SimpleGrantedAuthority("ROLE_platform-admin"),
                            new SimpleGrantedAuthority("ROLE_tenant-admin"));
            SecurityContextHolder.getContext()
                    .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
            TenantStatusGuard guard = mock(TenantStatusGuard.class);
            HttpServletRequest request = requestMock();
            when(request.getRequestURI()).thenReturn(uri);

            TenantContextFilter filter = new TenantContextFilter(Optional.of(guard));
            FilterChain chain = mock(FilterChain.class);
            HttpServletResponse response = mock(HttpServletResponse.class);
            PrintWriter writer = mock(PrintWriter.class);
            when(response.getWriter()).thenReturn(writer);

            filter.doFilterInternal(request, response, chain);

            verify(guard, never()).isUsable(anyString());
            verify(chain, never()).doFilter(any(), any());
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
            verify(writer).write(contains("\"status\":403"));
            assertTrue(TenantContext.current().isEmpty());
        }
    }

    @Test
    void tenantScopedPathMatchesOnlyTheTenantPrefix() {
        assertTrue(TenantContextFilter.isTenantScopedPath("/api/tenant"));
        assertTrue(TenantContextFilter.isTenantScopedPath("/api/tenant/settings"));
        assertFalse(TenantContextFilter.isTenantScopedPath("/api/tenants"));
        assertFalse(
                TenantContextFilter.isTenantScopedPath(
                        "/api/platform/tenants/t1/usage-quota/reset"));
        assertFalse(TenantContextFilter.isTenantScopedPath("/api/share/branding/t1/logo"));
        assertFalse(TenantContextFilter.isTenantScopedPath(null));
    }

    @Test
    void tenantContextIsAlwaysClearedAfterChainRunsEvenWhenAuthenticationIsAnonymous()
            throws Exception {
        TenantContextFilter filter = new TenantContextFilter(Optional.empty());
        filter.doFilterInternal(
                requestMock(), mock(HttpServletResponse.class), mock(FilterChain.class));

        assertNull(TenantContext.current().orElse(null));
    }

    @Test
    void theAsyncDispatchOfAStreamedResponseGetsTheTenantContextToo() throws Exception {
        // The thread that finishes an SSE request (and records platform issues on a stream error)
        // runs in
        // the ASYNC dispatch. OncePerRequestFilter skips that dispatch unless the filter opts in;
        // without
        // the opt-in the chain below saw no TenantContext and tenant-scoped writes hit a missing
        // schema.
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt("user-1", "tenant_b"),
                                List.of(new SimpleGrantedAuthority("ROLE_tenant-admin"))));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents/x/chat");
        request.setDispatcherType(DispatcherType.ASYNC);
        AtomicReference<TenantContext> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(TenantContext.current().orElse(null));

        new TenantContextFilter(Optional.empty())
                .doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("tenant_b", seen.get().getTenantId());
        assertTrue(TenantContext.current().isEmpty(), "cleared again once the dispatch returns");
    }

    @Test
    void aCommittedResponseOnTheAsyncDispatchIsNeitherWrittenNorFollowedByTheChain()
            throws Exception {
        // The tenant was stopped while an SSE body was streaming: the 403 can no longer be sent.
        // getWriter() on a response whose output stream was used throws IllegalStateException.
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt("user-1", "tenant_b"),
                                List.of(new SimpleGrantedAuthority("ROLE_tenant-admin"))));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        when(guard.isUsable("tenant_b")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/agents/x/chat");
        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getOutputStream().write("data: x\n\n".getBytes());
        response.flushBuffer();
        assertTrue(response.isCommitted());
        FilterChain chain = mock(FilterChain.class);

        new TenantContextFilter(Optional.of(guard)).doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertEquals(200, response.getStatus(), "status is not rewritten on a committed response");
        assertEquals("data: x\n\n", response.getContentAsString());
        assertTrue(TenantContext.current().isEmpty());
    }

    @Test
    void anUncommittedResponseStillGetsTheJson403() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt("user-1", "tenant_b"),
                                List.of(new SimpleGrantedAuthority("ROLE_tenant-admin"))));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        when(guard.isUsable("tenant_b")).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new TenantContextFilter(Optional.of(guard))
                .doFilter(
                        new MockHttpServletRequest("GET", "/api/agents"),
                        response,
                        mock(FilterChain.class));

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"status\":403"));
    }

    // --- V474 last-seen recording (2026-10-09) ---

    @Test
    void anAuthenticatedTenantRequestIsRecordedAsActivity() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(jwt("user-1", "tenant_b"), List.of()));
        UserActivityRecorder recorder = mock(UserActivityRecorder.class);
        TenantContextFilter filter =
                new TenantContextFilter(Optional.empty(), Optional.of(recorder));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(recorder).recordSeen("tenant_b", "user-1");
        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void aThrowingRecorderNeverFailsTheRequest() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(jwt("user-1", "tenant_b"), List.of()));
        UserActivityRecorder recorder = mock(UserActivityRecorder.class);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(recorder)
                .recordSeen(anyString(), anyString());
        TenantContextFilter filter =
                new TenantContextFilter(Optional.empty(), Optional.of(recorder));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(requestMock(), mock(HttpServletResponse.class), chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void rejectedOrTenantlessRequestsAreNotRecorded() throws Exception {
        UserActivityRecorder recorder = mock(UserActivityRecorder.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(mock(PrintWriter.class));

        // Unusable tenant -> 403 before the chain: not activity.
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(jwt("user-1", "tenant_b"), List.of()));
        TenantStatusGuard guard = mock(TenantStatusGuard.class);
        when(guard.isUsable("tenant_b")).thenReturn(false);
        new TenantContextFilter(Optional.of(guard), Optional.of(recorder))
                .doFilterInternal(requestMock(), response, mock(FilterChain.class));

        // Tenant-less platform admin: no app_user row to stamp, no tenant to attribute.
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt("ops-1", null),
                                List.of(new SimpleGrantedAuthority("ROLE_platform-admin"))));
        new TenantContextFilter(Optional.empty(), Optional.of(recorder))
                .doFilterInternal(requestMock(), response, mock(FilterChain.class));

        verify(recorder, never()).recordSeen(any(), any());
    }
}
