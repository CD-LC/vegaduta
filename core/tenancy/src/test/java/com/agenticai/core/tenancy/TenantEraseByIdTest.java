package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantDataErasureService#eraseTenantById}: the platform-admin "delete tenant completely"
 * control.
 */
class TenantEraseByIdTest extends TenantDataErasureFixture {

    // --- Platform-admin "delete tenant completely" control (2026-08-07): eraseTenantById -------

    @Test
    void eraseTenantByIdHappyPathRecordsAuditBeforeAndAfterAndReturnsSchemaName()
            throws SQLException {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));
        // The mcp_server_access_grant SELECT is the FIRST collectStringColumn
        // call inside eraseTenantById - the shared mocked ResultSet returns
        // one row then stops, same "one stub reused across every query"
        // trade-off the object-storage tests above already accept.
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString(1)).thenReturn("grant_1");

        TenantErasureResult result = service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1");

        org.mockito.InOrder order =
                org.mockito.Mockito.inOrder(
                        deletionAuditService, redisTenantDataPurgeService, statement);
        order.verify(deletionAuditService)
                .recordInitiated(
                        any(),
                        org.mockito.ArgumentMatchers.eq("Acme"),
                        org.mockito.ArgumentMatchers.eq("admin-1"));
        order.verify(redisTenantDataPurgeService)
                .purgeForTenant(org.mockito.ArgumentMatchers.eq(SCHEMA_NAME), any(), any());
        order.verify(statement, times(2)).execute(anyString());
        verify(deletionAuditService).recordCompleted(any(), anyString());
        verify(deletionAuditService, never()).recordFailed(any(), anyString(), anyString());
        assertEquals(TenantStatus.ERASED, tenant.getStatus());
        assertEquals(SCHEMA_NAME, result.schemaName());
    }

    @Test
    void eraseTenantByIdNameMismatchThrowsBeforeAnyAuditOrDeletion() throws SQLException {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));

        assertThrows(
                IllegalArgumentException.class,
                () -> service.eraseTenantById(UUID.randomUUID(), "Not Acme", "admin-1"));

        verify(deletionAuditService, never()).recordInitiated(any(), any(), any());
        verify(statement, never()).execute(anyString());
        assertFalse(tenant.getStatus() == TenantStatus.ERASED);
    }

    @Test
    void eraseTenantByIdUnknownTenantIdThrowsNotFound() {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.empty());

        assertThrows(
                TenantNotFoundByIdException.class,
                () -> service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1"));

        verify(deletionAuditService, never()).recordInitiated(any(), any(), any());
    }

    @Test
    void eraseTenantByIdAlreadyErasedThrowsConflictBeforeAnyAuditOrDeletion() throws SQLException {
        tenant.markErased("someone-else");
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));

        assertThrows(
                TenantAlreadyErasedException.class,
                () -> service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1"));

        verify(deletionAuditService, never()).recordInitiated(any(), any(), any());
        verify(statement, never()).execute(anyString());
    }

    @Test
    void eraseTenantByIdAbortsBeforeAnyDeletionWhenTheAuditWriteItselfFails() throws SQLException {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));
        when(deletionAuditService.recordInitiated(any(), any(), any()))
                .thenThrow(new RuntimeException("control_plane unreachable"));

        assertThrows(
                RuntimeException.class,
                () -> service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1"));

        verify(keycloakAdminClient, never()).listUsersByTenant(anyString());
        verify(statement, never()).execute(anyString());
        assertFalse(tenant.getStatus() == TenantStatus.ERASED);
    }

    @Test
    void eraseTenantByIdRecordsFailedOutcomeAndRethrowsWhenTheSchemaDropFails()
            throws SQLException {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));
        when(statement.execute(anyString()))
                .thenThrow(new SQLException("could not drop schema - open connections"));

        assertThrows(
                IllegalStateException.class,
                () -> service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1"));

        verify(deletionAuditService).recordInitiated(any(), any(), any());
        verify(deletionAuditService).recordFailed(any(), anyString(), anyString());
        verify(deletionAuditService, never()).recordCompleted(any(), anyString());
        verify(tenantRepository, never()).save(any());
        assertFalse(tenant.getStatus() == TenantStatus.ERASED);
    }

    @Test
    void eraseTenantByIdContinuesWhenTheRedisPurgeThrows() {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));
        org.mockito.Mockito.doThrow(new RuntimeException("redis unreachable"))
                .when(redisTenantDataPurgeService)
                .purgeForTenant(anyString(), any(), any());

        // Must not throw - the Redis purge is wrapped in its own outer
        // safety net specifically so this never blocks the rest of erasure.
        assertDoesNotThrow(() -> service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1"));

        assertEquals(TenantStatus.ERASED, tenant.getStatus());
        verify(deletionAuditService).recordCompleted(any(), anyString());
    }
}
