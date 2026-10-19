package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link TenantDataErasureService}: control-plane row cleanup and the tenant-row PII scrub
 * (2026-10-09).
 */
class TenantErasureCompletenessTest extends TenantDataErasureFixture {

    // --- Erasure completeness (2026-10-09): control-plane rows + tenant-row PII scrub ----------

    @Test
    void erasureDeletesTheTenantsAppUserRowsAfterAvatarsAndBeforeMarkingErased() {
        AppUser user = new AppUser(UUID.randomUUID(), "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);
        user.setAvatarUrl("avatars/kc-1.png");
        when(appUserRepository.findByTenantId(any())).thenReturn(List.of(user));

        service.eraseCurrentTenant("Acme");

        org.mockito.InOrder order =
                org.mockito.Mockito.inOrder(
                        fileStorageService, appUserRepository, tenantRepository);
        order.verify(fileStorageService).delete("avatars/kc-1.png");
        order.verify(appUserRepository).deleteAllByTenantId(tenant.getId());
        order.verify(tenantRepository).save(tenant);
    }

    @Test
    void anAppUserDeleteFailureFailsTheErasureAndTheTenantIsNotMarkedErased() {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));
        when(appUserRepository.deleteAllByTenantId(any()))
                .thenThrow(new RuntimeException("lock timeout"));

        assertThrows(
                RuntimeException.class,
                () -> service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1"));

        verify(deletionAuditService).recordFailed(any(), anyString(), anyString());
        verify(tenantRepository, never()).save(any());
        assertFalse(tenant.getStatus() == TenantStatus.ERASED);
    }

    @Test
    void everyVerifiedControlPlaneTableIsCleanedBySchemaNameWithPaymentsBeforeOffers()
            throws SQLException {
        service.eraseCurrentTenant("Acme");

        String all = String.join("\n", preparedSql);
        for (String table :
                List.of(
                        "checkout_session",
                        "commerce_product",
                        "agent_directory_entry",
                        "ai_content_report",
                        "x402_payment",
                        "x402_offer",
                        "tenant_sso_config",
                        "tenant_scim_token",
                        "channel_account_link",
                        "device_pairing_code",
                        "user_permission_grant",
                        "experiment_assignment",
                        "llm_gateway_content_policy",
                        "community_template_rating")) {
            assertTrue(all.contains("control_plane." + table), "missing cleanup for " + table);
        }
        // Retained for financial/audit reasons - never deleted.
        assertFalse(all.contains("billing_invoice"));
        assertFalse(all.contains("billing_webhook_event"));
        assertFalse(all.contains("platform_tenant_deletion_log"));
        assertFalse(all.contains("DELETE FROM control_plane.community_template "));
        assertTrue(
                all.contains(
                        "UPDATE control_plane.community_template SET source_tenant_id = NULL"));
        // x402_payment references x402_offer without a cascade.
        int payments = indexOfStatementFor("DELETE FROM control_plane.x402_payment");
        int offers = indexOfStatementFor("DELETE FROM control_plane.x402_offer");
        assertTrue(payments >= 0 && payments < offers);
        // Every placeholder is bound to the SCHEMA name (none of these columns hold the UUID).
        verify(preparedStatement, org.mockito.Mockito.atLeastOnce())
                .setString(
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.eq(SCHEMA_NAME));
        verify(preparedStatement, never())
                .setString(
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.argThat(v -> !SCHEMA_NAME.equals(v)));
    }

    @Test
    void aFailingControlPlaneTableIsBestEffortAndNamedInTheAuditRow() throws SQLException {
        when(tenantRepository.findById(any())).thenReturn(java.util.Optional.of(tenant));
        when(connection.prepareStatement(anyString()))
                .thenAnswer(
                        inv -> {
                            String sql = inv.getArgument(0);
                            preparedSql.add(sql);
                            if (sql.contains("control_plane.commerce_product")) {
                                throw new SQLException("relation does not exist");
                            }
                            return preparedStatement;
                        });

        assertDoesNotThrow(() -> service.eraseTenantById(UUID.randomUUID(), "Acme", "admin-1"));

        assertEquals(TenantStatus.ERASED, tenant.getStatus());
        // Later steps still ran.
        assertTrue(
                preparedSql.stream()
                        .anyMatch(sql -> sql.contains("control_plane.llm_gateway_content_policy")));
        org.mockito.ArgumentCaptor<String> steps =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(deletionAuditService).recordCompleted(any(), steps.capture());
        assertTrue(steps.getValue().contains("FAILED: commerce_product"));
    }

    @Test
    void erasureScrubsPersonalFieldsOnTheKeptTenantRow() {
        tenant.updateBranding("Acme Brand", "https://cdn.acme.test/logo.png");
        tenant.emergencyStop("Called by Jane Doe at +91 99999");

        service.eraseCurrentTenant("Acme");

        assertEquals(TenantStatus.ERASED, tenant.getStatus());
        assertEquals("Acme", tenant.getName());
        assertEquals(null, tenant.getBrandDisplayName());
        assertEquals(null, tenant.getBrandLogoUrl());
        assertEquals(null, tenant.getEmergencyStopReason());
    }

    private int indexOfStatementFor(String prefix) {
        for (int i = 0; i < preparedSql.size(); i++) {
            if (preparedSql.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }
}
