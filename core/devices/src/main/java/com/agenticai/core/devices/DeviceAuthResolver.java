package com.agenticai.core.devices;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Resolves an {@code X-Device-Token} header into a tenant schema (via
 * {@link TenantResolutionService}, under {@link ExternalAccountProvider#DEVICE})
 * and the specific {@link MobileDevice} row inside it - the one thing every
 * device-authenticated endpoint in {@code DeviceGatewayController} needs to
 * do before anything else, exactly the shape {@code WorkflowWebhookController}
 * uses for its token, factored out here since two endpoints (poll, result)
 * both need it rather than one.
 *
 * <p>Populates {@link TenantContext} for the duration of the resolved
 * action, always clearing it again in a {@code finally} block - callers
 * invoke {@link #run} rather than reaching into tenant resolution
 * themselves.
 */
@Service
public class DeviceAuthResolver {

    private final TenantResolutionService tenantResolutionService;
    private final MobileDeviceRepository deviceRepository;

    public DeviceAuthResolver(TenantResolutionService tenantResolutionService, MobileDeviceRepository deviceRepository) {
        this.tenantResolutionService = tenantResolutionService;
        this.deviceRepository = deviceRepository;
    }

    /**
     * Runs {@code action} with {@link TenantContext} populated for the
     * device that owns {@code deviceToken}, then always clears it again.
     * Throws {@link DeviceException} if the token is unknown or the device
     * has been revoked.
     *
     * <p>Deliberately does its own device lookup inline rather than
     * delegating to a {@code @Transactional} private/protected helper on
     * this same class - a same-class ("self-invocation") call to an
     * annotated method bypasses Spring's transactional proxy entirely (see
     * {@code WorkflowService#advanceSchedule}'s javadoc for the exact same
     * trap caught earlier in this codebase). {@link MobileDeviceRepository}
     * is a genuinely separate Spring Data proxy bean, so calling it directly
     * here needs no transactional wrapper of its own - the read runs in its
     * own implicit transaction, and every downstream mutation ({@link
     * DeviceCommandService#nextPendingCommand}, {@link
     * DeviceCommandService#recordResult}) already declares its own
     * {@code @Transactional} boundary.
     */
    public <T> T run(String deviceToken, java.util.function.Function<MobileDevice, T> action) {
        String tenantSchema = tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.DEVICE, deviceToken)
            .orElseThrow(() -> new DeviceException("Unknown device token."));

        TenantContext.set(TenantContext.of(tenantSchema, "system:device-gateway", Set.of()));
        try {
            MobileDevice device = deviceRepository.findByDeviceToken(deviceToken)
                .filter(d -> !d.isRevoked())
                .orElseThrow(() -> new DeviceException("Unknown or revoked device token."));
            return action.apply(device);
        } finally {
            TenantContext.clear();
        }
    }
}
