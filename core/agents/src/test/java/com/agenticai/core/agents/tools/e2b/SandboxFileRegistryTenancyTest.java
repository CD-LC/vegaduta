package com.agenticai.core.agents.tools.e2b;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.sdlc.E2bCredentialService;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class SandboxFileRegistryTenancyTest {

    private static final String CSV = "text/csv";

    private final AtomicLong now = new AtomicLong(1_000);
    private final UUID agentA = UUID.randomUUID();
    private final UUID agentB = UUID.randomUUID();

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static void as(String tenant) {
        TenantContext.set(TenantContext.of(tenant, "user", Set.of()));
    }

    private SandboxFileRegistry registry(
            int maxSessions, int perTenant, long tenantBytes, long totalBytes) {
        return new SandboxFileRegistry(
                true,
                6,
                1000,
                10_000,
                60_000,
                now::get,
                maxSessions,
                totalBytes,
                perTenant,
                tenantBytes);
    }

    @Test
    void aTenantOverItsSessionCapEvictsOnlyItsOwnOldestSession() {
        SandboxFileRegistry r = registry(50, 2, 1_000_000, 1_000_000);
        as("other");
        r.register(agentB, "b1", "d.csv", CSV, new byte[] {1});
        as("busy");
        r.register(agentA, "s1", "d.csv", CSV, new byte[] {1});
        r.register(agentA, "s2", "d.csv", CSV, new byte[] {1});
        r.register(agentA, "s3", "d.csv", CSV, new byte[] {1});

        assertTrue(
                r.filesFor(agentA, "s1").isEmpty(),
                "the busy tenant's oldest session is the one dropped");
        assertEquals(1, r.filesFor(agentA, "s2").size());
        assertEquals(1, r.filesFor(agentA, "s3").size());
        assertEquals(1, r.filesFor(agentB, "b1").size(), "another tenant's session must survive");
    }

    @Test
    void thePlatformWideCapEvictsFromTheTenantHoldingTheMostSessions() {
        SandboxFileRegistry r = registry(3, 10, 1_000_000, 1_000_000);
        as("heavy");
        r.register(agentA, "a1", "d.csv", CSV, new byte[] {1});
        r.register(agentA, "a2", "d.csv", CSV, new byte[] {1});
        as("quiet");
        r.register(agentB, "b1", "d.csv", CSV, new byte[] {1});
        r.register(agentB, "b2", "d.csv", CSV, new byte[] {1});

        assertTrue(r.filesFor(agentA, "a1").isEmpty(), "heavy tenant pays");
        assertEquals(1, r.filesFor(agentA, "a2").size());
        assertEquals(1, r.filesFor(agentB, "b1").size());
        assertEquals(1, r.filesFor(agentB, "b2").size());
    }

    @Test
    void aTenantByteCapEvictsOnlyThatTenantsOlderSessions() {
        SandboxFileRegistry r = registry(50, 10, 10, 1_000_000);
        as("other");
        r.register(agentB, "b1", "d.csv", CSV, new byte[8]);
        as("busy");
        r.register(agentA, "s1", "d.csv", CSV, new byte[8]);
        r.register(agentA, "s2", "d.csv", CSV, new byte[8]);

        assertTrue(r.filesFor(agentA, "s1").isEmpty());
        assertEquals(1, r.filesFor(agentA, "s2").size());
        assertEquals(1, r.filesFor(agentB, "b1").size());
    }

    @Test
    void aRefusedFileIsReportedOnceAsAWarning() {
        SandboxFileRegistry r = registry(50, 10, 1_000_000, 1_000_000);
        String reason = r.tryRegister(agentA, "s", "big.csv", CSV, new byte[2000]);
        assertNotNull(reason);
        assertTrue(reason.contains("big.csv"));

        List<String> warnings = r.drainWarnings(agentA, "s");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).startsWith("Not mounted:"));
        assertTrue(r.drainWarnings(agentA, "s").isEmpty(), "drained warnings are not repeated");
        assertNull(r.tryRegister(agentA, "s", "ok.csv", CSV, new byte[] {1}));
        assertTrue(r.drainWarnings(agentA, "s").isEmpty());
    }

    @Test
    void sessionFileCapRefusalIsAlsoReported() {
        SandboxFileRegistry r = new SandboxFileRegistry(true, 1, 1000, 10_000, 60_000, now::get);
        assertNull(r.tryRegister(agentA, "s", "a.csv", CSV, new byte[] {1}));
        assertNotNull(r.tryRegister(agentA, "s", "b.csv", CSV, new byte[] {1}));
        assertEquals(1, r.drainWarnings(agentA, "s").size());
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<E2bCredentialService> provider(E2bCredentialService service) {
        ObjectProvider<E2bCredentialService> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(service);
        return p;
    }

    @Test
    void availabilityNeedsFlagAgentAttachmentAndTheTenantsE2bKeyAndTemplate() {
        SandboxFileRegistry r = registry(50, 10, 1_000_000, 1_000_000);
        E2bCredentialService creds = mock(E2bCredentialService.class);
        r.setCredentialService(provider(creds));

        when(creds.isActive()).thenReturn(true);
        when(creds.hasCodeExecutionTemplate()).thenReturn(true);
        assertTrue(r.availableFor(true));
        assertFalse(r.availableFor(false), "agent without Code execution");

        when(creds.hasCodeExecutionTemplate()).thenReturn(false);
        assertFalse(r.availableFor(true), "no template id for this tenant");
        assertTrue(r.unavailableReason(true).contains("E2B"));

        when(creds.hasCodeExecutionTemplate()).thenReturn(true);
        when(creds.isActive()).thenReturn(false);
        assertFalse(r.availableFor(true), "no active key for this tenant");
    }

    @Test
    void availabilityFailsClosedWhenTheTenantCheckIsMissingOrThrows() {
        SandboxFileRegistry r = registry(50, 10, 1_000_000, 1_000_000);
        r.setCredentialService(provider(null));
        assertFalse(r.availableFor(true));

        E2bCredentialService creds = mock(E2bCredentialService.class);
        when(creds.isActive()).thenThrow(new IllegalStateException("db down"));
        r.setCredentialService(provider(creds));
        assertFalse(r.availableFor(true));
    }

    @Test
    void platformFlagOffIsNeverAvailable() {
        SandboxFileRegistry off = new SandboxFileRegistry(false, 6, 1000, 10_000, 60_000, now::get);
        assertFalse(off.availableFor(true));
    }
}
