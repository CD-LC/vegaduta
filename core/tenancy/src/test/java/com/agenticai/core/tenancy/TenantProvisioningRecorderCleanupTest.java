package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** B4: the failure path removes user rows; a failed attempt (and only that) can be discarded for a retry. */
class TenantProvisioningRecorderCleanupTest {

    private TenantRepository tenantRepository;
    private AppUserRepository appUserRepository;
    private TenantProvisioningRecorder recorder;
    private Tenant tenant;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        appUserRepository = mock(AppUserRepository.class);
        recorder = new TenantProvisioningRecorder(tenantRepository, appUserRepository);
        tenant = new Tenant("Acme", TenantPlan.FREE, "acme", "agentic-ai", AccountType.B2C, ExperienceMode.SIMPLE);
        tenantId = UUID.randomUUID();
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
    }

    @Test
    void markFailedAlsoDeletesTheTenantsUserRows() {
        AppUser row = new AppUser(tenantId, "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);
        when(appUserRepository.findByTenantId(tenantId)).thenReturn(List.of(row));

        recorder.markFailed(tenantId);

        verify(appUserRepository).delete(row);
        assertEquals(TenantStatus.PROVISIONING_FAILED, tenant.getStatus());
    }

    @Test
    void discardFailedTenantRemovesUsersAndTheTenantRow() {
        tenant.markProvisioningFailed();
        AppUser row = new AppUser(tenantId, "kc-1", "a@acme.test", UserRole.TENANT_ADMIN);
        when(appUserRepository.findByTenantId(tenantId)).thenReturn(List.of(row));

        assertTrue(recorder.discardFailedTenant(tenantId));

        verify(appUserRepository).delete(row);
        verify(tenantRepository).delete(tenant);
    }

    @Test
    void discardNeverTouchesATenantThatIsNotFailed() {
        // freshly created tenants are PROVISIONING (in flight) - must survive
        assertFalse(recorder.discardFailedTenant(tenantId));
        verify(tenantRepository, never()).delete(tenant);
        verify(appUserRepository, never()).delete(org.mockito.ArgumentMatchers.any());
    }
}
