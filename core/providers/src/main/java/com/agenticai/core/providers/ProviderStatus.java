package com.agenticai.core.providers;

/**
 * Activation status of a stored provider credential.
 */
public enum ProviderStatus {
    /** Validated successfully and usable by agents. */
    ACTIVE,
    /** Submitted but rejected by the provider (bad key, revoked, etc). */
    INVALID,
    /** Deliberately removed by a tenant-admin via DELETE /api/providers/{provider} - not usable by agents until reactivated. */
    INACTIVE
}
