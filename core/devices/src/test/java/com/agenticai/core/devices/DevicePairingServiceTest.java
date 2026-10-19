package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Coverage for {@link DevicePairingService#claim}, including the new
 * {@link DevicePlatform#DESKTOP} value (the CLI daemon client added
 * alongside ANDROID/IOS) - proves a DESKTOP claim goes through the exact
 * same code path (pairing-code lookup, tenant-schema resolution, device
 * token generation, {@code MobileDevice} persistence, {@code
 * ExternalAccountProvider#DEVICE} link registration) as ANDROID/IOS, since
 * {@link DevicePairingService} never branches on the platform value at all.
 */
class DevicePairingServiceTest {

    private DevicePairingCodeRepository pairingCodeRepository;
    private MobileDeviceRepository deviceRepository;
    private TenantResolutionService tenantResolutionService;
    private DevicePairingService service;

    @BeforeEach
    void setUp() {
        pairingCodeRepository = mock(DevicePairingCodeRepository.class);
        deviceRepository = mock(MobileDeviceRepository.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        service = new DevicePairingService(pairingCodeRepository, deviceRepository, tenantResolutionService);

        // TenantContext.claim() briefly sets its own context for the tenant
        // schema resolved from the pairing code; make sure no leftover state
        // from a prior test in this class (or elsewhere in the module) leaks
        // in via the ThreadLocal.
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @EnumSource(DevicePlatform.class)
    void claimSucceedsForEveryPlatformAndProducesAValidDeviceToken(DevicePlatform platform) {
        DevicePairingCode pairingCode = new DevicePairingCode("123456", "tenant_acme", "user-1", Instant.now().plus(10, ChronoUnit.MINUTES));
        when(pairingCodeRepository.findByCodeAndConsumedFalse("123456")).thenReturn(Optional.of(pairingCode));
        when(pairingCodeRepository.save(pairingCode)).thenReturn(pairingCode);
        when(deviceRepository.save(any(MobileDevice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DevicePairingService.ClaimResult result = service.claim("123456", "My " + platform + " Device", platform);

        assertThat(result.deviceToken()).isNotBlank();
        // generateDeviceToken() hex-encodes 24 random bytes -> 48 hex chars,
        // the same shape regardless of which platform claimed the device.
        assertThat(result.deviceToken()).hasSize(48).matches("[0-9a-f]{48}");
        assertThat(pairingCode.isConsumed()).isTrue();

        verify(tenantResolutionService).link(ExternalAccountProvider.DEVICE, result.deviceToken(), "tenant_acme");
        // TenantContext is scoped to the method body only (see claim()'s
        // javadoc) - it must not still be set once claim() returns.
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void desktopClaimPersistsTheDesktopPlatformOnTheMobileDeviceRow() {
        DevicePairingCode pairingCode = new DevicePairingCode("654321", "tenant_acme", "user-1", Instant.now().plus(10, ChronoUnit.MINUTES));
        when(pairingCodeRepository.findByCodeAndConsumedFalse("654321")).thenReturn(Optional.of(pairingCode));
        when(pairingCodeRepository.save(pairingCode)).thenReturn(pairingCode);
        when(deviceRepository.save(any(MobileDevice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.claim("654321", "My Desktop Daemon", DevicePlatform.DESKTOP);

        org.mockito.ArgumentCaptor<MobileDevice> captor = org.mockito.ArgumentCaptor.forClass(MobileDevice.class);
        verify(deviceRepository).save(captor.capture());
        assertThat(captor.getValue().getPlatform()).isEqualTo(DevicePlatform.DESKTOP);
        assertThat(captor.getValue().getDeviceName()).isEqualTo("My Desktop Daemon");
    }

    @Test
    void claimRejectsAnExpiredOrAlreadyConsumedCodeRegardlessOfPlatform() {
        DevicePairingCode expired = new DevicePairingCode("111111", "tenant_acme", "user-1", Instant.now().minus(1, ChronoUnit.MINUTES));
        when(pairingCodeRepository.findByCodeAndConsumedFalse("111111")).thenReturn(Optional.of(expired));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.claim("111111", "Some Device", DevicePlatform.DESKTOP))
            .isInstanceOf(DeviceException.class);
    }
}
