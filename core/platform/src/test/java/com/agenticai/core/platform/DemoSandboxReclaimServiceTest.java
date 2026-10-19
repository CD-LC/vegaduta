package com.agenticai.core.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.TenantNotFoundByIdException;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.demo.DemoSandboxDataPurger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * The reclaim's safety properties: the shared /try sandbox and every registered tenant are refused
 * for the WHOLE batch before anything is erased; each target goes through the regular (audited)
 * deletion path; one failing sandbox never stops the rest; and the demo-session rows are only
 * purged for sandboxes that were actually erased.
 */
class DemoSandboxReclaimServiceTest {

    private static final String SHARED = "demo_shared";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantDataErasureService erasure = mock(TenantDataErasureService.class);
    private final PlatformTenantDeletionOrchestrationService orchestration =
            mock(PlatformTenantDeletionOrchestrationService.class);
    private final DemoSandboxDataPurger purger = mock(DemoSandboxDataPurger.class);
    private final DemoSandboxReclaimService service =
            new DemoSandboxReclaimService(jdbc, tenants, erasure, orchestration, purger, SHARED);

    private Tenant tenant(String name, String schema) {
        Tenant t = mock(Tenant.class);
        UUID id = UUID.randomUUID();
        when(t.getId()).thenReturn(id);
        when(t.getName()).thenReturn(name);
        when(t.getSchemaName()).thenReturn(schema);
        when(tenants.findById(id)).thenReturn(Optional.of(t));
        return t;
    }

    private void remaining(long n) {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(SHARED))).thenReturn(n);
    }

    @Test
    void wrongConfirmationWordIsRefusedBeforeAnyLookup() {
        assertThrows(
                IllegalArgumentException.class, () -> service.reclaim(null, "reclaim", "ops-1"));
        assertThrows(IllegalArgumentException.class, () -> service.reclaim(null, null, "ops-1"));
        verify(orchestration, never()).deleteDemoTenant(any(), any(), any());
    }

    @Test
    void explicitIdsAreAllValidatedBeforeTheFirstErasure() {
        Tenant sandbox = tenant("Demo 1a2b3c4d", "demo_1a2b3c4d");
        Tenant registered = tenant("Acme", "acme");
        doThrow(new IllegalArgumentException("not a demo sandbox"))
                .when(erasure)
                .requireReclaimableDemo(registered, SHARED);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.reclaim(
                                List.of(sandbox.getId(), registered.getId()), "RECLAIM", "ops-1"));

        // The valid sandbox listed FIRST was not erased either - the batch is refused whole.
        verify(orchestration, never()).deleteDemoTenant(any(), any(), any());
        verify(purger, never()).purgeForTenant(any());
    }

    @Test
    void anUnknownIdIsANotFound() {
        UUID unknown = UUID.randomUUID();
        when(tenants.findById(unknown)).thenReturn(Optional.empty());
        assertThrows(
                TenantNotFoundByIdException.class,
                () -> service.reclaim(List.of(unknown), "RECLAIM", "ops-1"));
    }

    @Test
    void moreThanFiftyIdsIsRefused() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < DemoSandboxReclaimService.MAX_PER_CALL + 1; i++) {
            ids.add(UUID.randomUUID());
        }
        assertThrows(
                IllegalArgumentException.class, () -> service.reclaim(ids, "RECLAIM", "ops-1"));
    }

    @Test
    void eachSandboxIsErasedThroughTheAuditedPathAndAFailureDoesNotStopTheRest() {
        Tenant a = tenant("Demo aaaaaaaa", "demo_aaaaaaaa");
        Tenant b = tenant("Demo bbbbbbbb", "demo_bbbbbbbb");
        Tenant c = tenant("Demo cccccccc", "demo_cccccccc");
        UUID bId = b.getId();
        doThrow(new IllegalStateException("Failed to drop schema 'demo_bbbbbbbb'"))
                .when(orchestration)
                .deleteDemoTenant(bId, SHARED, "ops-1");
        when(purger.purgeForTenant(any())).thenReturn(List.of());
        remaining(7);

        var result = service.reclaim(List.of(a.getId(), b.getId(), c.getId()), "RECLAIM", "ops-1");

        assertEquals(List.of(a.getId(), c.getId()), result.erased());
        assertEquals(1, result.failed().size());
        assertEquals(b.getId(), result.failed().get(0).id());
        assertEquals("Demo bbbbbbbb", result.failed().get(0).name());
        assertTrue(result.failed().get(0).error().contains("demo_bbbbbbbb"));
        assertEquals(7, result.remaining());
        // Demo rows are purged only for sandboxes that were really erased.
        verify(purger).purgeForTenant(a.getId());
        verify(purger).purgeForTenant(c.getId());
        verify(purger, never()).purgeForTenant(b.getId());
    }

    @Test
    void noIdsMeansTheOldestReclaimableBatchStillReGuardedPerTenant() {
        Tenant a = tenant("Demo aaaaaaaa", "demo_aaaaaaaa");
        UUID aId = a.getId();
        when(jdbc.queryForList(anyString(), eq(UUID.class), eq(SHARED))).thenReturn(List.of(aId));
        when(purger.purgeForTenant(any())).thenReturn(List.of());
        remaining(0);

        var result = service.reclaim(Collections.emptyList(), "RECLAIM", "ops-1");

        verify(jdbc)
                .queryForList(
                        org.mockito.ArgumentMatchers.contains(
                                "ORDER BY t.created_at LIMIT "
                                        + DemoSandboxReclaimService.MAX_PER_CALL),
                        eq(UUID.class),
                        eq(SHARED));
        verify(erasure).requireReclaimableDemo(a, SHARED);
        verify(orchestration).deleteDemoTenant(a.getId(), SHARED, "ops-1");
        assertEquals(List.of(a.getId()), result.erased());
        assertEquals(0, result.remaining());
    }

    @Test
    void theReclaimableQueryExcludesTheSharedSchemaErasedRowsAndNonDemoTenants() {
        remaining(3);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(SHARED))).thenReturn(List.of());

        var listing = service.list();

        assertNull(listing.shared());
        assertEquals(3, listing.total());
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2))
                .query(sql.capture(), any(RowMapper.class), eq(SHARED));
        String reclaimable = sql.getAllValues().get(1);
        assertTrue(reclaimable.contains("t.is_demo"));
        assertTrue(reclaimable.contains("t.status <> 'ERASED'"));
        assertTrue(reclaimable.contains("t.schema_name <> ?"));
        assertTrue(reclaimable.contains("ORDER BY t.created_at"));
    }
}
