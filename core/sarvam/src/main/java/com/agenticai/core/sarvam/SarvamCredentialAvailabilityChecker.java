package com.agenticai.core.sarvam;

/**
 * Checks whether a Sarvam credential is configured for the current tenant.
 * Lives in {@code sarvam} (not {@code providers}) so that {@code providers}
 * can depend on {@code sarvam} for this type without {@code sarvam} needing
 * a reverse dependency on {@code providers} - see providers/pom.xml's
 * comment on the sarvam dependency for why that edge must stay one-way.
 */
public interface SarvamCredentialAvailabilityChecker {
    boolean isSarvamConfigured();
}
