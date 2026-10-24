package com.agenticai.core.settings;

/**
 * Two writers changed the same key at the same moment and this one lost (the store refused the second history row
 * of one version). Nothing was stored; the caller reloads the value and tries again. HTTP 409 {@code SETTING_CONFLICT}.
 */
public class SettingConflictException extends RuntimeException {

    private final String key;

    public SettingConflictException(String key, String message, Throwable cause) {
        super(message, cause);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
