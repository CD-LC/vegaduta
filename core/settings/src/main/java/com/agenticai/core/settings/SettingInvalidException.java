package com.agenticai.core.settings;

import java.util.List;

/** A value, scope or reason the catalog refuses. Rendered as HTTP 400 {@code {code: "SETTING_INVALID", key, message, min?, max?, enumValues?}}. */
public class SettingInvalidException extends RuntimeException {

    private final String key;
    private final Number min;
    private final Number max;
    private final List<String> enumValues;

    public SettingInvalidException(String key, String message) {
        this(key, message, null, null, null);
    }

    public SettingInvalidException(String key, String message, Number min, Number max, List<String> enumValues) {
        super(message);
        this.key = key;
        this.min = min;
        this.max = max;
        this.enumValues = enumValues == null || enumValues.isEmpty() ? null : List.copyOf(enumValues);
    }

    public String key() {
        return key;
    }

    public Number min() {
        return min;
    }

    public Number max() {
        return max;
    }

    public List<String> enumValues() {
        return enumValues;
    }
}
