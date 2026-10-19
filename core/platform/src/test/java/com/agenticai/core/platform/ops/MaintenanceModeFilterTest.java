package com.agenticai.core.platform.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class MaintenanceModeFilterTest {

    private final PlatformBroadcastService broadcast = mock(PlatformBroadcastService.class);
    private final MaintenanceModeFilter filter = new MaintenanceModeFilter(broadcast);

    @BeforeEach
    void maintenanceOn() {
        when(broadcast.maintenanceActive()).thenReturn(true);
        when(broadcast.maintenance()).thenReturn(new PlatformBroadcastService.MaintenanceState(true,
            "Back at 12:00 UTC", Instant.parse("2026-10-09T12:00:00Z"), Instant.parse("2026-10-09T11:00:00Z")));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse send(String method, String path, AtomicBoolean passed) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> passed.set(true));
        return res;
    }

    @Test
    void blocksOrdinaryApiCallsWith503AndTheMessage() throws Exception {
        AtomicBoolean passed = new AtomicBoolean();
        MockHttpServletResponse res = send("POST", "/api/agents/a1/chat", passed);
        assertEquals(503, res.getStatus());
        assertEquals(false, passed.get());
        assertTrue(res.getContentAsString().contains("\"error\":\"maintenance\""));
        assertTrue(res.getContentAsString().contains("Back at 12:00 UTC"));
        assertEquals("120", res.getHeader("Retry-After"));
    }

    @Test
    void exemptPathsAndPreflightsPassThrough() throws Exception {
        for (String path : List.of("/api/platform/maintenance", "/api/platform", "/api/public/status",
            "/api/system/status/live", "/api/billing/webhook/stripe", "/webhook/razorpay", "/actuator/health/readiness")) {
            AtomicBoolean passed = new AtomicBoolean();
            send("POST", path, passed);
            assertTrue(passed.get(), path);
        }
        AtomicBoolean preflight = new AtomicBoolean();
        send("OPTIONS", "/api/agents", preflight);
        assertTrue(preflight.get());
    }

    @Test
    void platformAdminsBypass() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "ops", "n/a", List.of(new SimpleGrantedAuthority("ROLE_platform-admin"))));
        AtomicBoolean passed = new AtomicBoolean();
        send("GET", "/api/agents", passed);
        assertTrue(passed.get());
    }

    @Test
    void ordinaryUsersDoNotBypass() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "u", "n/a", List.of(new SimpleGrantedAuthority("ROLE_tenant-admin"))));
        AtomicBoolean passed = new AtomicBoolean();
        assertEquals(503, send("GET", "/api/agents", passed).getStatus());
    }

    @Test
    void noMaintenanceMeansNoEffect() throws Exception {
        when(broadcast.maintenanceActive()).thenReturn(false);
        AtomicBoolean passed = new AtomicBoolean();
        send("GET", "/api/agents", passed);
        assertTrue(passed.get());
    }
}
