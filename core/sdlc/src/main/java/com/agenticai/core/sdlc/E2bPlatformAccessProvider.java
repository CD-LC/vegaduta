package com.agenticai.core.sdlc;

import java.util.Optional;

/**
 * SPI for a platform-managed E2B credential, so {@code core/sdlc} can fall back to one without
 * depending on {@code core/providers} (where the platform credential store lives). The single
 * implementation is {@code PlatformE2bAccessProvider} in {@code core/agents}; with no
 * implementation on the classpath there is simply no platform fallback.
 *
 * <p>Implementations must read the credential fresh on every call (it is admin-settable at
 * runtime) and must never throw for "not configured" - that is {@link Optional#empty()}.
 */
public interface E2bPlatformAccessProvider {

    /** The platform key + template as an {@link E2bAccess} with source PLATFORM, or empty when unset/disabled. */
    Optional<E2bAccess> resolve();

    /**
     * Cheap, non-decrypting form of {@code resolve().isPresent()} for availability checks that run
     * on every tool resolution. The default is correct but decrypts the key; override when the
     * store can answer without it.
     */
    default boolean isConfigured() {
        return resolve().isPresent();
    }
}
