package com.agenticai.core.settings;

/** The operator pinned this key ({@code app.settings.locked.<key>=true}); it cannot be changed through the API. HTTP 409 {@code SETTING_LOCKED}. */
public class SettingLockedException extends RuntimeException {

    private final String key;

    public SettingLockedException(String key, String reason) {
        super(reason);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
