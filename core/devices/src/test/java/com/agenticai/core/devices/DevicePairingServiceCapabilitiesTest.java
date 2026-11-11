package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The {@link DevicePairingService} methods the claim-flow test does not exercise: {@code
 * generateCode} (console side, needs a {@link TenantContext}), {@code registerAuthenticated} (the
 * JWT alternative to the code flow) and {@code updateCapabilities} (the device-declared capability
 * CSV, normalised server-side).
 */
class DevicePairingServiceCapabilitiesTest {

    private DevicePairingCodeRepository pairingCodeRepository;
    private MobileDeviceRepository deviceRepository;
    private TenantResolutionService tenantResolutionService;
    private DevicePairingService service;

    @BeforeEach
    void setUp() {
        pairingCodeRepository = mock(DevicePairingCodeRepository.class);
        deviceRepository = mock(MobileDeviceRepository.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        service =
                new DevicePairingService(
                        pairingCodeRepository, deviceRepository, tenantResolutionService);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void as(String tenant, String subject) {
        TenantContext.set(TenantContext.of(tenant, subject, Set.of()));
    }

    @Test
    void generateCodeStoresASixDigitCodeForTheCurrentTenant() {
        as("tenant_acme", "user-1");
        when(pairingCodeRepository.findByCodeAndConsumedFalse(anyString()))
                .thenReturn(Optional.empty());
        when(pairingCodeRepository.save(any(DevicePairingCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Instant before = Instant.now();
        DevicePairingCode code = service.generateCode();

        assertThat(code.getCode()).matches("\\d{6}");
        assertThat(code.getTenantSchema()).isEqualTo("tenant_acme");
        assertThat(code.getCreatedBy()).isEqualTo("user-1");
        assertThat(code.getExpiresAt())
                .isAfter(before.plus(Duration.ofMinutes(9)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(11)));
        assertThat(code.isConsumed()).isFalse();
    }

    @Test
    void generateCodeRetriesWhenTheFirstDrawCollidesWithALiveCode() {
        as("tenant_acme", "user-1");
        // First generated value is taken, anything after that is free.
        when(pairingCodeRepository.findByCodeAndConsumedFalse(anyString()))
                .thenReturn(Optional.of(mock(DevicePairingCode.class)))
                .thenReturn(Optional.empty());
        when(pairingCodeRepository.save(any(DevicePairingCode.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.generateCode();

        verify(pairingCodeRepository, org.mockito.Mockito.times(2))
                .findByCodeAndConsumedFalse(anyString());
    }

    @Test
    void generateCodeRefusesToRunWithoutATenantContext() {
        assertThatThrownBy(() -> service.generateCode()).isInstanceOf(DeviceException.class);
        verify(pairingCodeRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void registerAuthenticatedMintsADeviceAndLinksItsTokenForTheJwtTenant() {
        as("tenant_globex", "user-9");
        when(deviceRepository.save(any(MobileDevice.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DevicePairingService.ClaimResult result =
                service.registerAuthenticated("Work Phone", DevicePlatform.IOS);

        assertThat(result.deviceToken()).hasSize(48).matches("[0-9a-f]{48}");

        ArgumentCaptor<MobileDevice> captor = ArgumentCaptor.forClass(MobileDevice.class);
        verify(deviceRepository).save(captor.capture());
        assertThat(captor.getValue().getDeviceName()).isEqualTo("Work Phone");
        assertThat(captor.getValue().getPlatform()).isEqualTo(DevicePlatform.IOS);
        assertThat(captor.getValue().getDeviceToken()).isEqualTo(result.deviceToken());

        verify(tenantResolutionService)
                .link(ExternalAccountProvider.DEVICE, result.deviceToken(), "tenant_globex");
        // Unlike claim(), the caller's context is left exactly as it was found.
        assertThat(TenantContext.current().map(TenantContext::getTenantId))
                .contains("tenant_globex");
    }

    @Test
    void registerAuthenticatedRefusesToRunWithoutATenantContext() {
        assertThatThrownBy(() -> service.registerAuthenticated("Phone", DevicePlatform.ANDROID))
                .isInstanceOf(DeviceException.class);
    }

    @Test
    void updateCapabilitiesNormalisesToASortedDedupedLowercaseCsv() {
        MobileDevice device = new MobileDevice("Pixel", DevicePlatform.ANDROID, "tok");

        service.updateCapabilities(
                device, Arrays.asList("  Location.Get ", "camera.photo", "CAMERA.PHOTO", "", null));

        assertThat(device.getCapabilities()).isEqualTo("camera.photo,location.get");
        verify(deviceRepository).save(device);
    }

    @Test
    void updateCapabilitiesStoresNullForAnEmptyOrNullList() {
        MobileDevice device = new MobileDevice("Pixel", DevicePlatform.ANDROID, "tok");
        device.setCapabilities("camera.photo");

        service.updateCapabilities(device, null);
        assertThat(device.getCapabilities()).isNull();

        device.setCapabilities("camera.photo");
        service.updateCapabilities(device, List.of(" ", ""));
        assertThat(device.getCapabilities()).isNull();
    }
}
