package com.agenticai.core.devices;

/**
 * Whether a {@link DeviceAppUsageEvent} marks an app coming to the
 * foreground or leaving it - mirrors Android's own {@code UsageEvents.Event}
 * MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND constants (see {@code
 * AppUsageReporter} in the Mobile SDK), collapsed to just these two since
 * that's all an agent answering "what has this person been using" needs.
 */
public enum DeviceAppUsageEventType {
    FOREGROUND,
    BACKGROUND
}
