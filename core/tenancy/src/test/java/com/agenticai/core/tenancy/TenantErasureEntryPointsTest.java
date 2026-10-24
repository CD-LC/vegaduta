package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Every way a tenant can be erased - the platform admin's {@code eraseTenantById}, the tenant
 * admin's {@code eraseCurrentTenant} ({@code POST /api/tenant/data-deletion}) and the sole member's
 * {@code eraseCurrentTenantForSoleMemberAccountDeletion} ({@code DELETE /api/account}) - runs the
 * one audit-before-delete + Valkey purge + erasure sequence. The two self-service paths used to
 * skip the Valkey purge, and {@code eraseCurrentTenant} also skipped the platform deletion-audit
 * row, so a workspace that erased itself left its usage counters behind and left no trace for the
 * platform admin.
 */
class TenantErasureEntryPointsTest extends TenantDataErasureFixture {

    @Test
    void tenantAdminSelfServiceErasureAuditsPurgesValkeyThenErases() throws SQLException {
        service.eraseCurrentTenant("Acme");

        assertAuditedPurgedThenErased("self-service: ");
        assertEquals(TenantStatus.ERASED, tenant.getStatus());
    }

    @Test
    void soleMemberAccountDeletionAuditsPurgesValkeyThenErases() throws SQLException {
        service.eraseCurrentTenantForSoleMemberAccountDeletion();

        assertAuditedPurgedThenErased("account self-deletion: ");
        assertEquals(TenantStatus.ERASED, tenant.getStatus());
    }

    @Test
    void platformAdminErasureAuditsPurgesValkeyThenErases() throws SQLException {
        when(tenantRepository.findById(any())).thenReturn(Optional.of(tenant));

        service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1");

        assertAuditedPurgedThenErased("");
    }

    @Test
    void aWrongConfirmationNamePurgesAndAuditsNothing() {
        assertThrows(IllegalArgumentException.class, () -> service.eraseCurrentTenant("Not Acme"));

        verify(deletionAuditService, never()).recordInitiated(any(), any(), any());
        verify(redisTenantDataPurgeService, never()).purgeForTenant(any(), any(), any());
    }

    @Test
    void aFailedSelfServiceDropIsAuditedAsFailedAndLeavesTheTenantUnerased() throws SQLException {
        when(statement.execute(anyString())).thenThrow(new SQLException("open connections"));

        assertThrows(IllegalStateException.class, () -> service.eraseCurrentTenant("Acme"));

        verify(deletionAuditService).recordFailed(any(), anyString(), anyString());
        verify(deletionAuditService, never()).recordCompleted(any(), anyString());
        verify(tenantRepository, never()).save(any());
    }

    @Test
    void aRedisOutageDoesNotStopASelfServiceErasure() {
        org.mockito.Mockito.doThrow(new IllegalStateException("valkey down"))
                .when(redisTenantDataPurgeService)
                .purgeForTenant(any(), any(), any());

        service.eraseCurrentTenant("Acme");

        assertEquals(TenantStatus.ERASED, tenant.getStatus());
        verify(deletionAuditService).recordCompleted(any(), anyString());
    }

    private void assertAuditedPurgedThenErased(String expectedAuditPrefix) throws SQLException {
        InOrder order = inOrder(deletionAuditService, redisTenantDataPurgeService, statement);
        order.verify(deletionAuditService).recordInitiated(any(), eq("Acme"), any());
        order.verify(redisTenantDataPurgeService).purgeForTenant(eq(SCHEMA_NAME), any(), any());
        order.verify(statement, org.mockito.Mockito.atLeastOnce()).execute(anyString());
        order.verify(deletionAuditService)
                .recordCompleted(
                        any(), org.mockito.ArgumentMatchers.startsWith(expectedAuditPrefix));
    }
}
