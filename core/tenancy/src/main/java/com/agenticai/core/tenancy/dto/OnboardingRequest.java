package com.agenticai.core.tenancy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/onboarding} - the social-login onboarding step.
 * Only the organization name is collected (identity comes from the JWT); like
 * {@link SignupRequest}, the required Terms/Privacy acceptance is UI-gating
 * only and {@code newsOptIn} is the optional product-updates opt-in.
 *
 * <p>{@code demoToken} is the same optional demo-conversion passthrough
 * {@link SignupRequest#demoToken()} documents - and it matters MOST here:
 * social login is the sandbox visitor's one-tap path to a real account, so
 * this is the highest-value conversion edge to record. Never a credential,
 * never a precondition; junk is silently ignored.
 */
public record OnboardingRequest(
    @NotBlank(message = "orgName is required") @Size(max = 255) String orgName,
    Boolean newsOptIn,
    @Size(max = 512, message = "demoToken is too long") String demoToken
) {
    public boolean newsOptInOrDefault() {
        return newsOptIn != null && newsOptIn;
    }

    /** Trimmed demo token, or null when absent/blank - the shape {@code DemoConversionService} expects. */
    public String demoTokenOrNull() {
        if (demoToken == null || demoToken.isBlank()) {
            return null;
        }
        return demoToken.trim();
    }
}
