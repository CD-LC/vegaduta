package com.agenticai.core.devices.sentinel;

/** Event severity, ordered: {@code INFO < WATCH < ALERT < CRITICAL}. Decided on-device by deterministic rules. */
public enum SentinelSeverity {
    INFO,
    WATCH,
    ALERT,
    CRITICAL;

    public boolean atLeast(SentinelSeverity other) {
        return compareTo(other) >= 0;
    }
}
