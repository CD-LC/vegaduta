package com.agenticai.core.platform;

/** Body for {@code PUT /api/platform/settings/{key}} - just the new value, key comes from the path. */
public record PlatformSettingUpdateRequest(String value) {
}
