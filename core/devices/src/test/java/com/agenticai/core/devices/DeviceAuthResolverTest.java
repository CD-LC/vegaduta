package com.agenticai.core.devices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DeviceAuthResolver#run} is the whole security posture of the device gateway: resolve the
 * bare device token to a tenant schema, run the action inside that {@link TenantContext}, and
 * always clear it again - including when the action throws.
 */
class DeviceAuthResolverTest {

    private TenantResolutionService tenantResolutionService;
    private MobileDeviceRepository deviceRepository;
    private DeviceAuthResolver resolver;

    @BeforeEach
    void setUp() {
        tenantResolutionService = mock(TenantResolutionService.class);
        deviceRepository = mock(MobileDeviceRepository.class);
        resolver = new DeviceAuthResolver(tenantResolutionService, deviceRepository);
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void runExecutesTheActionUnderTheResolvedTenantSchemaAndClearsAfterwards() {
        MobileDevice device = new MobileDevice("Pixel", DevicePlatform.ANDROID, "tok-1");
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.DEVICE, "tok-1"))
                .thenReturn(Optional.of("tenant_acme"));
        when(deviceRepository.findByDeviceToken("tok-1")).thenReturn(Optional.of(device));

        AtomicReference<TenantContext> inside = new AtomicReference<>();
        MobileDevice result = resolver.run("tok-1", d -> {
            inside.set(TenantContext.current().orElseThrow());
            return d;
        });

        assertThat(result).isSameAs(device);
        assertThat(inside.get().getTenantId()).isEqualTo("tenant_acme");
        assertThat(inside.get().getSubject()).isEqualTo("system:device-gateway");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void anUnknownTokenNeverReachesTheDeviceLookup() {
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.DEVICE, "nope"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.run("nope", d -> d))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("Unknown device token");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aRevokedDeviceIsRejectedTheSameAsAnUnknownOne() {
        MobileDevice device = new MobileDevice("Pixel", DevicePlatform.ANDROID, "tok-1");
        device.revoke();
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.DEVICE, "tok-1"))
                .thenReturn(Optional.of("tenant_acme"));
        when(deviceRepository.findByDeviceToken("tok-1")).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> resolver.run("tok-1", d -> d))
                .isInstanceOf(DeviceException.class)
                .hasMessageContaining("revoked");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void theTenantContextIsClearedEvenWhenTheActionThrows() {
        MobileDevice device = new MobileDevice("Pixel", DevicePlatform.ANDROID, "tok-1");
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.DEVICE, "tok-1"))
                .thenReturn(Optional.of("tenant_acme"));
        when(deviceRepository.findByDeviceToken("tok-1")).thenReturn(Optional.of(device));

        assertThatThrownBy(
                        () ->
                                resolver.run(
                                        "tok-1",
                                        d -> {
                                            throw new IllegalStateException("boom");
                                        }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void aTokenThatResolvesToNoRowAtAllIsRejected() {
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.DEVICE, "ghost"))
                .thenReturn(Optional.of("tenant_acme"));
        when(deviceRepository.findByDeviceToken("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.run("ghost", d -> d)).isInstanceOf(DeviceException.class);
        verify(deviceRepository).findByDeviceToken("ghost");
        assertThat(TenantContext.current()).isEmpty();
    }
}
