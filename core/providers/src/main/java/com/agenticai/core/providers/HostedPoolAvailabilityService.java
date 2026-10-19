package com.agenticai.core.providers;

import com.agenticai.core.security.HostedPoolAvailability;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cheap, time-boxed answer to "is there a platform-hosted pool at all?".
 * Called from the chat hot path (via {@code TenantLimits#usesPlatformPool}), so
 * the two-row read is cached for a few seconds - the same posture as
 * {@code FreeTierUsageLimiterService}'s quota-config cache. Fails closed to
 * "not configured" on a read error so a database blip degrades to BYOK rather
 * than to a pool that may be empty.
 */
@Component
public class HostedPoolAvailabilityService implements HostedPoolAvailability {

    private record Cached(boolean configured, long expiresAtMs) {}

    private final PlatformHostedLlmProviderRepository repository;
    private final HostedLlmPoolSettingsRepository settingsRepository;
    private final long cacheMs;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    public HostedPoolAvailabilityService(
        PlatformHostedLlmProviderRepository repository,
        HostedLlmPoolSettingsRepository settingsRepository,
        @Value("${app.hosted-pool.availability-cache-ms:10000}") long cacheMs
    ) {
        this.repository = repository;
        this.settingsRepository = settingsRepository;
        this.cacheMs = cacheMs;
    }

    @Override
    public boolean isPoolConfigured() {
        long now = System.currentTimeMillis();
        Cached cached = cache.get();
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.configured();
        }
        boolean configured;
        try {
            // No settings row yet means the default (enabled) applies - see
            // PlatformHostedLlmProviderService#loadOrCreateSettings.
            boolean masterOn = settingsRepository.findById(HostedLlmPoolSettings.SINGLETON_ID)
                .map(HostedLlmPoolSettings::isEnabled)
                .orElse(true);
            configured = masterOn && repository.existsByEnabledTrue();
        } catch (RuntimeException e) {
            configured = false;
        }
        cache.set(new Cached(configured, now + cacheMs));
        return configured;
    }
}
