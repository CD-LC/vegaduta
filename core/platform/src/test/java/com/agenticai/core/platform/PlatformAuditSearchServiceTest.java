package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogEntry;
import com.agenticai.core.audit.AuditLogRepository;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class PlatformAuditSearchServiceTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final AuditLogRepository repo = mock(AuditLogRepository.class);
    private final PlatformAuditSearchService service = new PlatformAuditSearchService(tenants, repo);
    private static final PlatformAuditSearchService.Filters NONE =
        new PlatformAuditSearchService.Filters(null, null, null, null, null, null, null);

    @BeforeEach
    void operator() {
        TenantContext.set(TenantContext.of(null, "operator-1", Set.of("ROLE_platform-admin")));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static Tenant tenant(String name, String schema) {
        Tenant t = new Tenant(name, TenantPlan.FREE, schema, "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        t.markActive();
        return t;
    }

    private static AuditLogEntry entry(String action) {
        return new AuditLogEntry("actor", action, "keycloak_user", "u1", AuditOutcome.SUCCESS, "d");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eachTenantIsQueriedUnderItsOwnSchemaAndCallerContextIsRestored() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(tenant("Acme", "acme"), tenant("Globex", "globex")));
        List<String> seen = new ArrayList<>();
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(inv -> {
            seen.add(TenantContext.current().map(TenantContext::getTenantId).orElse(null));
            return new PageImpl<>(List.of(entry("platform.user.enable")));
        });

        var result = service.search(NONE, 50);

        assertEquals(List.of("acme", "globex"), seen);
        assertEquals(2, result.rows().size());
        assertEquals(2, result.tenantsScanned());
        assertFalse(result.truncated());
        assertEquals("operator-1", TenantContext.current().orElseThrow().getSubject());
        assertNull(TenantContext.current().orElseThrow().getTenantId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantFilterOnlyMatchesKnownSchemasAndUnknownScansNothing() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(tenant("Acme", "acme")));
        var forged = new PlatformAuditSearchService.Filters("public; drop", null, null, null, null, null, null);

        var result = service.search(forged, 10);

        assertEquals(0, result.tenantsScanned());
        assertTrue(result.rows().isEmpty());
        verify(repo, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hittingThePerTenantLimitMarksTruncatedAndOverallLimitIsEnforced() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(tenant("A", "a"), tenant("B", "b")));
        when(repo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entry("x"), entry("y"))));

        var result = service.search(NONE, 2);

        assertTrue(result.truncated());
        assertEquals(2, result.rows().size());
        assertEquals(2, result.rowLimit());
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantsBeyondTheCapAreNotScannedAndFlagged() {
        List<Tenant> many = new ArrayList<>();
        for (int i = 0; i < PlatformAuditSearchService.MAX_TENANTS + 5; i++) {
            many.add(tenant("T" + i, String.format("t%04d", i)));
        }
        when(tenants.findActiveExcludingDemo()).thenReturn(many);
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        var result = service.search(NONE, 10);

        assertEquals(PlatformAuditSearchService.MAX_TENANTS, result.tenantsScanned());
        assertEquals(PlatformAuditSearchService.MAX_TENANTS + 5, result.tenantsInScope());
        assertTrue(result.truncated());
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneFailingTenantIsSkippedAndCounted() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(tenant("A", "a"), tenant("B", "b")));
        AtomicInteger calls = new AtomicInteger();
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            return new PageImpl<>(List.of(entry("x")));
        });

        var result = service.search(NONE, 10);

        assertEquals(1, result.tenantsFailed());
        assertEquals(1, result.rows().size());
    }

    @Test
    void unknownOutcomeIsRejected() {
        var bad = new PlatformAuditSearchService.Filters(null, null, null, null, "NOPE", null, null);
        assertThrows(IllegalArgumentException.class, () -> service.search(bad, 10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void callerContextIsRestoredEvenWhenNoTenantsMatch() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of());
        service.search(NONE, 10);
        assertEquals("operator-1", TenantContext.current().orElseThrow().getSubject());
        verify(repo, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void offsetSkipsNewestRowsAndEachTenantIsReadUpToOffsetPlusLimit() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(tenant("A", "a"), tenant("B", "b")));
        List<Integer> pageSizes = new ArrayList<>();
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenAnswer(inv -> {
            pageSizes.add(((Pageable) inv.getArgument(1)).getPageSize());
            return new PageImpl<>(List.of(entry("x"), entry("y"), entry("z")));
        });

        var result = service.search(NONE, 2, 3);

        assertEquals(List.of(5, 5), pageSizes);
        assertEquals(2, result.rows().size());
        assertEquals(3, result.offset());
        // 6 merged rows, window is 3..5 -> 1 row is left after it
        assertEquals(6, result.knownTotal());
        assertTrue(result.hasMore());
    }

    @Test
    @SuppressWarnings("unchecked")
    void lastPageHasNoMoreAndOffsetBeyondTheEndIsEmpty() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(tenant("A", "a")));
        when(repo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entry("x"), entry("y"), entry("z"))));

        var last = service.search(NONE, 10, 2);
        assertEquals(1, last.rows().size());
        assertFalse(last.hasMore());

        var beyond = service.search(NONE, 10, 50);
        assertTrue(beyond.rows().isEmpty());
        assertFalse(beyond.hasMore());
    }

    @Test
    @SuppressWarnings("unchecked")
    void negativeAndHugeOffsetsAreClamped() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(tenant("A", "a")));
        when(repo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        assertEquals(0, service.search(NONE, 10, -4).offset());
        assertEquals(PlatformAuditSearchService.MAX_OFFSET, service.search(NONE, 10, Integer.MAX_VALUE).offset());
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoArgSearchIsOffsetZero() {
        when(tenants.findActiveExcludingDemo()).thenReturn(List.of(tenant("A", "a")));
        when(repo.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(entry("x"))));
        var result = service.search(NONE, 10);
        assertEquals(0, result.offset());
        assertEquals(1, result.rows().size());
    }
}
