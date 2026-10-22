package com.agenticai.core.sarvam;

import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Component;

/**
 * Implementation of {@link SarvamCredentialAvailabilityChecker} that checks
 * if Sarvam is configured for the current tenant.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
public class SarvamCredentialAvailabilityCheckerImpl implements SarvamCredentialAvailabilityChecker {

    private final SarvamCredentialService credentialService;

    public SarvamCredentialAvailabilityCheckerImpl(SarvamCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public boolean isSarvamConfigured() {
        try {
            // Try to resolve the active Sarvam API key
            // If this succeeds, Sarvam is configured for this tenant
            credentialService.resolveActiveApiKey();
            return true;
        } catch (SarvamNotConfiguredException e) {
            // Not configured - expected case
            return false;
        } catch (Exception e) {
            // Any other error - be conservative and assume not available
            return false;
        }
    }
}
