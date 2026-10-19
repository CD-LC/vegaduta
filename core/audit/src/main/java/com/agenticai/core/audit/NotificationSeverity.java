package com.agenticai.core.audit;

/**
 * Notification bell - how loudly a {@link Notification} should surface.
 * Deliberately 3 levels, one more than {@link IssueSeverity}'s 2
 * (ERROR/WARNING): notifications include purely informational ones
 * (e.g. "your workflow finished") that never become a {@link PlatformIssue}
 * ticket at all.
 */
public enum NotificationSeverity {
    INFO,
    WARNING,
    ERROR
}
