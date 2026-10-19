package com.agenticai.core.devices;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two-step handshake that gets a phone from "just installed the app" to
 * "trusted, tenant-scoped {@link MobileDevice}":
 *
 * <ol>
 *   <li>{@link #generateCode()} - called from an authenticated console
 *   request (any signed-in tenant member, not admin-only - pairing your own
 *   phone is a personal utility action, the same trust level as creating an
 *   agent or a workflow, not a platform-wide credential change). Stores a
 *   6-digit code in {@code control_plane.device_pairing_code} against the
 *   caller's current tenant schema, valid for {@link #CODE_TTL}.</li>
 *   <li>{@link #claim(String, String, DevicePlatform)} - called from the
 *   Mobile SDK app itself with no JWT at all (a phone can't hold a Keycloak
 *   session), carrying only the code the user just typed in. Resolves the
 *   tenant schema from that code, creates the {@link MobileDevice} row
 *   inside it, and - critically - also registers the newly-minted device
 *   token in {@code control_plane} via {@link TenantResolutionService} under
 *   {@link ExternalAccountProvider#DEVICE}, so every later device request
 *   (poll, result) can resolve straight from the token without the
 *   short-lived pairing code being involved again.</li>
 * </ol>
 */
@Service
public class DevicePairingService {

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final DevicePairingCodeRepository pairingCodeRepository;
    private final MobileDeviceRepository deviceRepository;
    private final TenantResolutionService tenantResolutionService;

    public DevicePairingService(
        DevicePairingCodeRepository pairingCodeRepository,
        MobileDeviceRepository deviceRepository,
        TenantResolutionService tenantResolutionService
    ) {
        this.pairingCodeRepository = pairingCodeRepository;
        this.deviceRepository = deviceRepository;
        this.tenantResolutionService = tenantResolutionService;
    }

    @Transactional
    public DevicePairingCode generateCode() {
        String tenantSchema = TenantContext.current()
            .map(TenantContext::getTenantId)
            .orElseThrow(() -> new DeviceException("No tenant context - are you signed in?"));
        String createdBy = TenantContext.current().map(TenantContext::getSubject).orElse("unknown");

        // SAST audit fix (2026-07-27) - was ThreadLocalRandom, which is
        // explicitly documented as unsuitable for security-sensitive use
        // (predictable from its seed/prior outputs). This code is the only
        // thing standing between an anonymous caller and claiming someone
        // else's phone-pairing session (see DeviceClaimRateLimiter's javadoc
        // for the fuller threat model) - it deserves the same
        // SecureRandom this class already uses for the device token itself.
        String code;
        int attempts = 0;
        do {
            code = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
            attempts++;
        } while (pairingCodeRepository.findByCodeAndConsumedFalse(code).isPresent() && attempts < 10);

        DevicePairingCode pairingCode = new DevicePairingCode(code, tenantSchema, createdBy, Instant.now().plus(CODE_TTL));
        return pairingCodeRepository.save(pairingCode);
    }

    /**
     * Runs entirely without {@code TenantContext} being pre-populated - the
     * caller ({@code DeviceGatewayController}) has none to give it, this is
     * the very call that discovers which schema to use. Sets it here, for
     * the duration of this method only, the same "resolve first, then set,
     * then clear in a finally" shape {@code WorkflowWebhookController} uses.
     *
     * <p><b>Deliberately NOT {@code @Transactional} at the method level</b> -
     * that was the original shape, and it silently wrote every claimed
     * device into the {@code public} schema instead of the tenant's own.
     * Hibernate's {@code CurrentTenantIdentifierResolver} is only consulted
     * once, when a Session is opened - and Spring's {@code @Transactional}
     * proxy opens that Session at method entry, before this method's body
     * (and therefore {@link TenantContext#set}) ever runs. The first
     * repository touch below it, {@code findByCodeAndConsumedFalse}, would
     * lock that Session's schema to the (tenant-less) default for the rest
     * of the method - so the later {@code deviceRepository.save(...)} call
     * reused that same stale Session/connection even though {@code
     * TenantContext} was already correctly set by the time it ran. No
     * exception, no warning - {@code public.mobile_device} is a perfectly
     * valid write target, just the wrong one (Flyway's tenant/ migrations
     * apply there too, unqualified). Each Spring Data repository call below
     * now gets its own implicit, freshly-opened transaction instead, so its
     * Session's tenant identifier is resolved fresh, correctly, at the
     * moment {@code TenantContext} actually reflects the claimed device's
     * tenant. {@code registerAuthenticated()} below never had this bug -
     * its caller already has {@code TenantContext} set (by the JWT filter
     * chain) before Spring even opens that method's Session.
     */
    public ClaimResult claim(String code, String deviceName, DevicePlatform platform) {
        DevicePairingCode pairingCode = pairingCodeRepository.findByCodeAndConsumedFalse(code)
            .filter(pc -> pc.isUsable(Instant.now()))
            .orElseThrow(() -> new DeviceException("Pairing code is invalid or has expired - generate a new one from the console."));

        pairingCode.consume();
        // Explicit save required now that this method has no enclosing
        // transaction of its own - findByCodeAndConsumedFalse's own
        // (Spring-Data-default) transaction already committed and detached
        // this entity by the time consume() mutates it in memory, so
        // there's no active persistence context left to dirty-check it.
        pairingCodeRepository.save(pairingCode);
        String tenantSchema = pairingCode.getTenantSchema();

        TenantContext.set(TenantContext.of(tenantSchema, "system:device-pairing", java.util.Set.of()));
        try {
            String deviceToken = generateDeviceToken();
            MobileDevice device = deviceRepository.save(new MobileDevice(deviceName, platform, deviceToken));
            tenantResolutionService.link(ExternalAccountProvider.DEVICE, deviceToken, tenantSchema);
            return new ClaimResult(device.getId(), deviceToken);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * #157 follow-up ("reuse existing Keycloak IdP brokering") - the Mobile
     * SDK's alternative to the pairing-code handshake above, for a caller
     * that already carries a Keycloak JWT (see {@code
     * RegisterDeviceRequest}'s javadoc). Unlike {@link #claim}, no
     * TenantContext gymnastics are needed - {@code DeviceController}'s
     * caller already ran through {@code SecurityConfig}'s JWT filter chain,
     * so {@link TenantContext#current()} is already populated exactly the
     * way any other authenticated console endpoint sees it.
     */
    @Transactional
    public ClaimResult registerAuthenticated(String deviceName, DevicePlatform platform) {
        String tenantSchema = TenantContext.current()
            .map(TenantContext::getTenantId)
            .orElseThrow(() -> new DeviceException("No tenant context - are you signed in?"));
        String deviceToken = generateDeviceToken();
        MobileDevice device = deviceRepository.save(new MobileDevice(deviceName, platform, deviceToken));
        tenantResolutionService.link(ExternalAccountProvider.DEVICE, deviceToken, tenantSchema);
        return new ClaimResult(device.getId(), deviceToken);
    }

    private static String generateDeviceToken() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Stores the device's declared capability keys (normalised to a sorted,
     * deduplicated, lower-case CSV). Called through {@code
     * DeviceGatewayController} inside {@code DeviceAuthResolver.run}, so the
     * tenant context is already the device's own schema when this runs.
     */
    public void updateCapabilities(MobileDevice device, java.util.List<String> capabilities) {
        String csv = capabilities == null ? "" : capabilities.stream()
            .filter(c -> c != null && !c.isBlank())
            .map(c -> c.trim().toLowerCase(java.util.Locale.ROOT))
            .distinct()
            .sorted()
            .collect(java.util.stream.Collectors.joining(","));
        device.setCapabilities(csv.isEmpty() ? null : csv);
        deviceRepository.save(device);
    }

    public record ClaimResult(java.util.UUID deviceId, String deviceToken) {
    }
}
