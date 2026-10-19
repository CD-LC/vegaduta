package com.agenticai.core.platform;

import com.agenticai.core.providers.ProviderName;
import java.time.Instant;

/**
 * Wire shape of {@link TranslationJobSettings} for {@link PlatformController}
 * (JAVA-016: controllers no longer return JPA entities). Field names match the
 * entity's previous JSON exactly, so the contract is unchanged.
 */
public record TranslationJobSettingsResponse(
    Long id,
    String cronScheduleDisplay,
    ProviderName providerName,
    String model,
    String workflowPathHint,
    String notes,
    Instant updatedAt,
    String updatedBy,
    Instant lastTriggeredAt,
    String lastTriggeredBy,
    String lastTriggerStatus,
    String lastTriggerMessage
) {
    public static TranslationJobSettingsResponse from(TranslationJobSettings s) {
        return new TranslationJobSettingsResponse(
            s.getId(), s.getCronScheduleDisplay(), s.getProviderName(), s.getModel(), s.getWorkflowPathHint(),
            s.getNotes(), s.getUpdatedAt(), s.getUpdatedBy(), s.getLastTriggeredAt(), s.getLastTriggeredBy(),
            s.getLastTriggerStatus(), s.getLastTriggerMessage());
    }
}
