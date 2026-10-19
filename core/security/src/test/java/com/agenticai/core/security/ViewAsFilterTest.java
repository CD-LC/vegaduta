package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.security.ImpersonationGrantResolver.ViewAsSession;
import jakarta.servlet.FilterChain;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Security tests for read-only "view as user": who may use a grant, which requests it unlocks, and
 * that everything downstream sees the TARGET user and the SESSION's tenant - never the admin's.
 */
class ViewAsFilterTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String GRANT = "raw-grant-secret-value-1234567890abcdefghijk";
    private static final String ADMIN = "admin-kc-1";
    private static final UUID SESSION_ID = UUID.randomUUID();

    private ImpersonationGrantResolver resolver;
    private ViewAsFilter filter;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        resolver = mock(ImpersonationGrantResolver.class);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(
                new GranularPermissionConverter(Optional.empty()));
        filter =
                new ViewAsFilter(
                        Optional.of(resolver), converter, Clock.fixed(NOW, ZoneOffset.UTC));
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ViewAsFilter.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ViewAsFilter.class)).detachAppender(logs);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        ImpersonationContext.clear();
    }

    // ------------------------------------------------------------------ fixtures

    private static ViewAsSession session(String admin, Instant expiresAt, Instant endedAt) {
        return new ViewAsSession(
                SESSION_ID,
                admin,
                "ops@vegaduta.ai",
                UUID.randomUUID(),
                "tenant_target",
                "Target Co",
                "target-kc-9",
                "user@target.co",
                List.of("tenant-admin", "platform-admin", "user"),
                expiresAt,
                endedAt);
    }

    private static ViewAsSession active() {
        return session(ADMIN, NOW.plusSeconds(600), null);
    }

    private static JwtAuthenticationToken token(String subject, String tenantId, String... roles) {
        Jwt.Builder b =
                Jwt.withTokenValue("admin-token")
                        .header("alg", "RS256")
                        .subject(subject)
                        .issuer("https://auth.vegaduta.ai/realms/agentic-ai")
                        .issuedAt(NOW.minusSeconds(60))
                        .expiresAt(NOW.plusSeconds(3600))
                        .claim("email", subject + "@vegaduta.ai");
        if (tenantId != null) {
            b.claim("tenant_id", tenantId);
        }
        List<GrantedAuthority> authorities =
                java.util.Arrays.stream(roles)
                        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                        .toList();
        return new JwtAuthenticationToken(b.build(), authorities);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.addHeader(ViewAsFilter.HEADER, GRANT);
        return r;
    }

    private record Seen(
            Authentication auth,
            Optional<ImpersonationContext.Session> impersonation,
            String tenant) {}

    /** Runs the filter, then TenantContextFilter, recording what the endpoint would see. */
    private MockHttpServletResponse run(MockHttpServletRequest request, AtomicReference<Seen> seen)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        TenantContextFilter tenantFilter = new TenantContextFilter(Optional.empty());
        FilterChain endpoint =
                (req, res) ->
                        seen.set(
                                new Seen(
                                        SecurityContextHolder.getContext().getAuthentication(),
                                        ImpersonationContext.current(),
                                        TenantContext.current()
                                                .map(TenantContext::getTenantId)
                                                .orElse(null)));
        filter.doFilter(request, response, (req, res) -> tenantFilter.doFilter(req, res, endpoint));
        return response;
    }

    // ------------------------------------------------------------------ pass-through

    @Test
    void requestWithoutHeaderIsUntouched() throws Exception {
        JwtAuthenticationToken admin = token(ADMIN, null, "platform-admin");
        SecurityContextHolder.getContext().setAuthentication(admin);
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse res = run(new MockHttpServletRequest("POST", "/api/agents"), seen);
        assertEquals(200, res.getStatus());
        assertSame(admin, seen.get().auth());
        assertTrue(seen.get().impersonation().isEmpty());
        verify(resolver, never()).resolve(any());
    }

    @Test
    void publicStatusEndpointIgnoresTheHeaderEvenWithAnAdminToken() throws Exception {
        JwtAuthenticationToken admin = token(ADMIN, null, "platform-admin");
        SecurityContextHolder.getContext().setAuthentication(admin);
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse res = run(request("GET", "/api/system/status/live"), seen);
        assertEquals(200, res.getStatus());
        assertSame(admin, seen.get().auth());
        assertTrue(seen.get().impersonation().isEmpty());
        verify(resolver, never()).resolve(any());
    }

    @Test
    void anonymousCallerIgnoresTheHeaderAndNeverErrors() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new AnonymousAuthenticationToken(
                                "k", "anon", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse res = run(request("GET", "/api/agents"), seen);
        assertEquals(200, res.getStatus());
        assertTrue(seen.get().impersonation().isEmpty());
        verify(resolver, never()).resolve(any());
    }

    // ------------------------------------------------------------------ rejections

    @Test
    void nonAdminTokenWithAGrantIsRejected() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(token("someone", "tenant_a", "tenant-admin"));
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse res = run(request("GET", "/api/agents"), seen);
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("\"error\":\"view_as_invalid\""));
        assertNull(seen.get());
        verify(resolver, never()).resolve(any());
    }

    @Test
    void unknownGrantIsRejectedAndNeverFallsBackToTheAdmin() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(token(ADMIN, null, "platform-admin"));
        when(resolver.resolve(GRANT)).thenReturn(Optional.empty());
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse res = run(request("GET", "/api/agents"), seen);
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("view_as_invalid"));
        assertNull(seen.get());
    }

    @Test
    void grantStartedByAnotherAdminIsRejected() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(token("other-admin", null, "platform-admin"));
        when(resolver.resolve(GRANT)).thenReturn(Optional.of(active()));
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse res = run(request("GET", "/api/agents"), seen);
        assertEquals(401, res.getStatus());
        assertTrue(res.getContentAsString().contains("view_as_invalid"));
        assertNull(seen.get());
        verify(resolver, never()).recordRequest(any());
    }

    @Test
    void expiredSessionAnswers410() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(token(ADMIN, null, "platform-admin"));
        when(resolver.resolve(GRANT))
                .thenReturn(Optional.of(session(ADMIN, NOW.minusSeconds(1), null)));
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse res = run(request("GET", "/api/agents"), seen);
        assertEquals(410, res.getStatus());
        assertTrue(res.getContentAsString().contains("\"error\":\"view_as_expired\""));
        assertNull(seen.get());
    }

    @Test
    void endedSessionAnswers410() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(token(ADMIN, null, "platform-admin"));
        when(resolver.resolve(GRANT))
                .thenReturn(Optional.of(session(ADMIN, NOW.plusSeconds(600), NOW.minusSeconds(5))));
        AtomicReference<Seen> seen = new AtomicReference<>();
        assertEquals(410, run(request("GET", "/api/agents"), seen).getStatus());
        assertNull(seen.get());
    }

    @Test
    void writeMethodsAreBlocked() throws Exception {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            SecurityContextHolder.getContext()
                    .setAuthentication(token(ADMIN, null, "platform-admin"));
            when(resolver.resolve(GRANT)).thenReturn(Optional.of(active()));
            AtomicReference<Seen> seen = new AtomicReference<>();
            MockHttpServletResponse res = run(request(method, "/api/agents/1/chat"), seen);
            assertEquals(403, res.getStatus(), method);
            assertTrue(
                    res.getContentAsString().contains("\"error\":\"impersonation_read_only\""),
                    method);
            assertNull(seen.get(), method);
        }
    }

    @Test
    void sideEffectingGetsAreBlocked() throws Exception {
        for (String path :
                List.of(
                        "/api/channels/google/authorize-url",
                        "/oauth/google/callback",
                        "/api/mcp/oauth/remote",
                        "/api/audit-log/export",
                        "/api/public-ish/unsubscribe/x",
                        "/api/tenant/scim-tokens",
                        "/api/platform/tenants",
                        "/api/agents/1/chats/2/export")) {
            SecurityContextHolder.getContext()
                    .setAuthentication(token(ADMIN, null, "platform-admin"));
            when(resolver.resolve(GRANT)).thenReturn(Optional.of(active()));
            AtomicReference<Seen> seen = new AtomicReference<>();
            MockHttpServletResponse res = run(request("GET", path), seen);
            assertEquals(403, res.getStatus(), path);
            assertNull(seen.get(), path);
        }
    }

    @Test
    void websocketUpgradeIsBlocked() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(token(ADMIN, null, "platform-admin"));
        when(resolver.resolve(GRANT)).thenReturn(Optional.of(active()));
        MockHttpServletRequest req = request("GET", "/api/agents/1/live");
        req.addHeader("Upgrade", "websocket");
        AtomicReference<Seen> seen = new AtomicReference<>();
        assertEquals(403, run(req, seen).getStatus());
        assertNull(seen.get());
    }

    @Test
    void lookupFailureFailsClosed() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(token(ADMIN, null, "platform-admin"));
        when(resolver.resolve(GRANT)).thenThrow(new IllegalStateException("db down"));
        AtomicReference<Seen> seen = new AtomicReference<>();
        assertEquals(401, run(request("GET", "/api/agents"), seen).getStatus());
        assertNull(seen.get());
    }

    @Test
    void missingResolverRejectsInsteadOfIgnoring() throws Exception {
        filter =
                new ViewAsFilter(
                        Optional.empty(),
                        new JwtAuthenticationConverter(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        SecurityContextHolder.getContext().setAuthentication(token(ADMIN, null, "platform-admin"));
        AtomicReference<Seen> seen = new AtomicReference<>();
        assertEquals(401, run(request("GET", "/api/agents"), seen).getStatus());
        assertNull(seen.get());
    }

    // ------------------------------------------------------------------ honoured request

    @Test
    void honouredRequestRunsAsTargetInTheSessionTenantThenRestoresTheAdmin() throws Exception {
        JwtAuthenticationToken admin = token(ADMIN, "admins_own_tenant", "platform-admin");
        SecurityContextHolder.getContext().setAuthentication(admin);
        when(resolver.resolve(GRANT)).thenReturn(Optional.of(active()));
        AtomicReference<Seen> seen = new AtomicReference<>();

        MockHttpServletResponse res = run(request("GET", "/api/agents"), seen);

        assertEquals(200, res.getStatus());
        assertEquals("no-store", res.getHeader("Cache-Control"));
        Authentication auth = seen.get().auth();
        assertTrue(auth instanceof JwtAuthenticationToken);
        Jwt jwt = ((JwtAuthenticationToken) auth).getToken();
        assertEquals("target-kc-9", jwt.getSubject());
        assertEquals("target-kc-9", auth.getName());
        assertEquals("tenant_target", jwt.getClaimAsString("tenant_id"));
        assertEquals("user@target.co", jwt.getClaimAsString("email"));
        assertEquals(ADMIN, ((Map<?, ?>) jwt.getClaim("act")).get("sub"));
        assertTrue(
                jwt.getExpiresAt().compareTo(NOW.plusSeconds(600)) <= 0,
                "never outlives the session");
        Set<String> authorities =
                auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.toSet());
        assertTrue(authorities.contains("ROLE_tenant-admin"));
        assertFalse(
                authorities.contains("ROLE_platform-admin"),
                "platform-admin is never carried into view-as");
        // Tenant resolution comes from the session, not the admin's own token.
        assertEquals("tenant_target", seen.get().tenant());
        assertEquals(SESSION_ID, seen.get().impersonation().orElseThrow().sessionId());
        verify(resolver).recordRequest(SESSION_ID);

        // Restored afterwards.
        assertSame(admin, SecurityContextHolder.getContext().getAuthentication());
        assertFalse(ImpersonationContext.isActive());
    }

    @Test
    void rawGrantIsNeverLogged() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(token(ADMIN, null, "platform-admin"));
        when(resolver.resolve(GRANT)).thenReturn(Optional.of(active()));
        run(request("GET", "/api/agents"), new AtomicReference<>());
        run(request("POST", "/api/agents"), new AtomicReference<>());
        SecurityContextHolder.getContext()
                .setAuthentication(token("other-admin", null, "platform-admin"));
        run(request("GET", "/api/agents"), new AtomicReference<>());

        assertFalse(logs.list.isEmpty());
        for (ILoggingEvent event : logs.list) {
            assertFalse(event.getFormattedMessage().contains(GRANT), event.getFormattedMessage());
        }
        assertTrue(
                logs.list.stream()
                        .anyMatch(
                                e ->
                                        e.getFormattedMessage().contains("session=" + SESSION_ID)
                                                && e.getFormattedMessage().contains("method=GET")
                                                && e.getFormattedMessage()
                                                        .contains("path=/api/agents")));
    }

    @Test
    void activityIsNotRecordedForTheTargetDuringViewAs() throws Exception {
        UserActivityRecorder recorder = mock(UserActivityRecorder.class);
        SecurityContextHolder.getContext().setAuthentication(token(ADMIN, null, "platform-admin"));
        when(resolver.resolve(GRANT)).thenReturn(Optional.of(active()));
        TenantContextFilter tenantFilter =
                new TenantContextFilter(Optional.empty(), Optional.of(recorder));
        filter.doFilter(
                request("GET", "/api/agents"),
                new MockHttpServletResponse(),
                (req, res) -> tenantFilter.doFilter(req, res, (a, b) -> {}));
        verify(recorder, never()).recordSeen(any(), any());
    }

    @Test
    void assertNotImpersonatingThrowsOnlyDuringViewAs() {
        ImpersonationContext.assertNotImpersonating("llm-call");
        ImpersonationContext.set(
                new ImpersonationContext.Session(SESSION_ID, ADMIN, null, "t", "u"));
        ImpersonationReadOnlyException e =
                assertThrows(
                        ImpersonationReadOnlyException.class,
                        () -> ImpersonationContext.assertNotImpersonating("email-send"));
        assertEquals("email-send", e.getAction());
        assertNotNull(e.getMessage());
    }

    @Test
    void denylistAndPublicPathsMatchAsIntended() {
        assertTrue(ViewAsFilter.isDenied("/api/channels/x/authorize-url"));
        assertFalse(ViewAsFilter.isDenied("/api/agents/1/connectors"));
        assertFalse(ViewAsFilter.isDenied("/api/view-as/session"));
        assertTrue(ViewAsFilter.isPublicPath("/api/public/status"));
        assertFalse(ViewAsFilter.isPublicPath("/api/agents"));
    }
}
