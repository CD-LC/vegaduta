package com.agenticai.core.devices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One foreground/background transition a paired {@link MobileDevice}
 * observed via Android's {@code UsageStatsManager} (see {@code
 * AppUsageReporter} in the Mobile SDK - requires the user to grant the
 * PACKAGE_USAGE_STATS special app-op permission, the standard non-rooted way
 * to read app-usage history). Append-only log, same posture as {@link
 * DeviceNotification}.
 */
@Entity
@Table(name = "device_app_usage_event")
public class DeviceAppUsageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "package_name", nullable = false, length = 255)
    private String packageName;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 16)
    private DeviceAppUsageEventType eventType;

    @Column(name = "event_time")
    private Instant eventTime;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected DeviceAppUsageEvent() {
        // JPA
    }

    public DeviceAppUsageEvent(UUID deviceId, String packageName, DeviceAppUsageEventType eventType, Instant eventTime) {
        this.deviceId = deviceId;
        this.packageName = packageName;
        this.eventType = eventType;
        this.eventTime = eventTime;
        this.receivedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getPackageName() {
        return packageName;
    }

    public DeviceAppUsageEventType getEventType() {
        return eventType;
    }

    public Instant getEventTime() {
        return eventTime;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
