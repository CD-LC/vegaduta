package com.agenticai.core.devices;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One notification a paired {@link MobileDevice} observed, reported by the
 * Mobile SDK's {@code DeviceNotificationListenerService} (an Android
 * {@code NotificationListenerService}, the standard non-rooted API for this -
 * same capability class as {@code AutomationAccessibilityService} is for
 * screen automation). Append-only log, unlike {@link DeviceScreenState} -
 * "what notifications came in recently" is inherently a history question.
 */
@Entity
@Table(name = "device_notification")
public class DeviceNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "package_name", nullable = false, length = 255)
    private String packageName;

    @Column(length = 500)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String text;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected DeviceNotification() {
        // JPA
    }

    public DeviceNotification(UUID deviceId, String packageName, String title, String text, Instant postedAt) {
        this.deviceId = deviceId;
        this.packageName = packageName;
        this.title = title;
        this.text = text;
        this.postedAt = postedAt;
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

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
