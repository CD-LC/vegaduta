package com.agenticai.core.platform;

import com.agenticai.core.tenancy.PlatformSetting;
import java.time.Instant;

/**
 * Wire shape for the "central control in admin platform maintenance to set
 * all these values in UI" settings list - see {@link
 * com.agenticai.core.tenancy.PlatformSettingsService}'s javadoc for the
 * backing table/service. A thin projection of {@link PlatformSetting}
 * itself, kept as its own DTO rather than serializing the entity directly,
 * same "never expose a JPA entity straight out of a controller" posture
 * every other response in this controller already follows.
 */
public record PlatformSettingResponse(
    String key, String value, String description, String updatedBy, Instant updatedAt
) {
    public static PlatformSettingResponse from(PlatformSetting setting) {
        return new PlatformSettingResponse(
            setting.getKey(), setting.getValue(), setting.getDescription(),
            setting.getUpdatedBy(), setting.getUpdatedAt()
        );
    }
}
