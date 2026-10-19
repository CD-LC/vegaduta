package com.agenticai.core.tenancy.dto;

import com.agenticai.core.security.ExperienceMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code usePlatformLlm} is deliberately nullable/optional (not {@code
 * @NotNull}) - issue report: "every non free tenant should get an option to
 * select use platform llm," toggled independently of {@code experienceMode}.
 * A {@code null} value on a PUT means "leave the tenant's current setting
 * unchanged," so existing callers that only send {@code experienceMode}
 * keep working with no behavior change - see {@code TenantSettingsController
 * #updateExperienceMode}.
 *
 * <p>{@code brandDisplayName}/{@code brandLogoUrl} (white-label minimum-real
 * build, PRICING-MONETIZATION-AUDIT-2026-07-31.md item C2) follow the exact
 * same null-means-unchanged convention; a BLANK string explicitly clears the
 * stored value back to platform branding. Sending either non-null requires
 * the plan's {@code white_label} entitlement (403 otherwise) and the logo URL
 * must be https - both enforced in {@code TenantSettingsController}, which
 * also re-checks the lengths so the rules hold even off the bean-validation
 * path. The {@code @Size} caps here mirror the V162 column widths.
 */
public record UpdateExperienceModeRequest(
    @NotNull ExperienceMode experienceMode,
    Boolean usePlatformLlm,
    @Size(max = 64) String brandDisplayName,
    @Size(max = 512) String brandLogoUrl
) {
}
