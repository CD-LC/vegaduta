package com.agenticai.core.platform;

import com.agenticai.core.providers.ProviderName;

/**
 * Request body for {@code PUT /api/platform/translation-job}. Every field
 * is optional/nullable - {@link TranslationJobSettingsService#update} only
 * overwrites fields that are actually supplied. {@code updatedBy} is not
 * part of this record - {@code PlatformController} derives it from the
 * caller's own {@code com.agenticai.core.security.TenantContext} subject,
 * the same way {@code UserAdminController} derives {@code actorId()}.
 */
public record TranslationJobSettingsUpdateRequest(
    String cronScheduleDisplay,
    ProviderName providerName,
    String model,
    String workflowPathHint,
    String notes
) {
}
