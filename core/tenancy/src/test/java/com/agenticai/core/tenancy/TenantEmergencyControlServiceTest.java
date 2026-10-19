package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantPlan;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * No test previously existed for {@link TenantEmergencyControlService} - the
 * write side of the #815 fail-safe kill switch: both the platform-wide
 * emergency stop and the per-tenant one that {@link TenantStatusGuardImpl}
 * (already covered by {@code TenantStatusGuardImplTest}) reads on every
 * request. A bug on this write side is just as dangerous as a bug in the
 * read side - e.g. a reason/updatedBy mixup that silently fails to persist,
 * or resume leaving stale state behind, would either fail to lock out an
 * incident tenant or leave one wrongly locked out after being cleared.
 * Covers: stop/resume for both the per-tenant and platform-wide levers, the
 * blank/null reason defaulting both share, and the fail-loud paths this
 * class's own javadoc documents (unknown tenant id, missing kill-switch
 * singleton row).
 */
class TenantEmergencyControlServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private TenantRepository tenantRepository;
    private PlatformKillSwitchRepository platformKillSwitchRepository;
    private TenantEmergencyControlService service;

    @BeforeEach
    void setUp() {
        tenantRepository = mock(TenantRepository.class);
        platformKillSwitchRepository = mock(PlatformKillSwitchRepository.class);
        service = new TenantEmergencyControlService(tenantRepository, platformKillSwitchRepository);
    }

    private static Tenant tenantWithId() {
        Tenant tenant = new Tenant("Acme", TenantPlan.PREMIUM, "acme", "agentic-ai", AccountType.B2C, ExperienceMode.ADVANCED);
        ReflectionTestUtils.setField(tenant, "id", TENANT_ID);
        return tenant;
    }

    @Test
    void stopTenantMarksItEmergencyStoppedWithTheGivenReason() {
        Tenant tenant = tenantWithId();
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        service.stopTenant(TENANT_ID, "Suspicious billing activity", "platform-admin-1");

        assertTrue(tenant.isEmergencyStopped());
        assertEquals("Suspicious billing activity", tenant.getEmergencyStopReason());
    }

    @Test
    void stopTenantWithABlankReasonDefaultsToNoReasonGiven() {
        Tenant tenant = tenantWithId();
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        service.stopTenant(TENANT_ID, "   ", "platform-admin-1");

        assertEquals("No reason given", tenant.getEmergencyStopReason());
    }

    @Test
    void stopTenantWithANullReasonDefaultsToNoReasonGiven() {
        Tenant tenant = tenantWithId();
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        service.stopTenant(TENANT_ID, null, "platform-admin-1");

        assertEquals("No reason given", tenant.getEmergencyStopReason());
    }

    @Test
    void stopTenantForAnUnknownTenantIdThrowsRatherThanSilentlyDoingNothing() {
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        NoSuchElementException thrown = assertThrows(
            NoSuchElementException.class, () -> service.stopTenant(TENANT_ID, "reason", "platform-admin-1")
        );
        assertTrue(thrown.getMessage().contains(TENANT_ID.toString()));
    }

    @Test
    void resumeTenantClearsEmergencyStopState() {
        Tenant tenant = tenantWithId();
        tenant.emergencyStop("Suspicious billing activity");
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.of(tenant));

        service.resumeTenant(TENANT_ID);

        assertFalse(tenant.isEmergencyStopped());
        assertNull(tenant.getEmergencyStopReason());
        assertNull(tenant.getEmergencyStoppedAt());
    }

    @Test
    void resumeTenantForAnUnknownTenantIdThrowsRatherThanSilentlyDoingNothing() {
        when(tenantRepository.findById(TENANT_ID)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.resumeTenant(TENANT_ID));
    }

    @Test
    void stopPlatformStopsTheSingletonKillSwitchWithReasonAndUpdatedBy() {
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.of(killSwitch));

        service.stopPlatform("Incident #99", "platform-admin-2");

        assertTrue(killSwitch.isStopped());
        assertEquals("Incident #99", killSwitch.getReason());
        assertEquals("platform-admin-2", killSwitch.getUpdatedBy());
    }

    @Test
    void stopPlatformWithABlankReasonDefaultsToNoReasonGiven() {
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.of(killSwitch));

        service.stopPlatform("", "platform-admin-2");

        assertEquals("No reason given", killSwitch.getReason());
    }

    @Test
    void resumePlatformClearsTheSingletonKillSwitch() {
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        killSwitch.stop("Incident #99", "platform-admin-2");
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.of(killSwitch));

        service.resumePlatform("platform-admin-3");

        assertFalse(killSwitch.isStopped());
        assertNull(killSwitch.getReason());
        assertNull(killSwitch.getStoppedAt());
        assertEquals("platform-admin-3", killSwitch.getUpdatedBy());
    }

    @Test
    void isPlatformStoppedReflectsTheSingletonKillSwitchState() {
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        killSwitch.stop("Incident #99", "platform-admin-2");
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.of(killSwitch));

        assertTrue(service.isPlatformStopped());
    }

    @Test
    void getStatusReturnsTheCurrentKillSwitchStateVerbatim() {
        PlatformKillSwitch killSwitch = new PlatformKillSwitch();
        killSwitch.stop("Incident #99", "platform-admin-2");
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.of(killSwitch));

        PlatformKillSwitchStatus status = service.getStatus();

        assertTrue(status.stopped());
        assertEquals("Incident #99", status.reason());
        assertEquals(killSwitch.getStoppedAt(), status.stoppedAt());
        assertEquals("platform-admin-2", status.updatedBy());
    }

    @Test
    void missingKillSwitchSingletonRowFailsLoudOnStopPlatformRatherThanSilentlyNoOpping() {
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.stopPlatform("Incident #99", "platform-admin-2"));
    }

    @Test
    void missingKillSwitchSingletonRowFailsLoudOnIsPlatformStoppedRatherThanFailingOpenOrClosedSilently() {
        when(platformKillSwitchRepository.findById(PlatformKillSwitch.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.isPlatformStopped());
    }
}
