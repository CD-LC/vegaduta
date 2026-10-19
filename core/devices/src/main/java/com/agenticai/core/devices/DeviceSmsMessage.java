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
 * One SMS message a paired {@link MobileDevice} observed, reported by the
 * Mobile SDK's {@code SmsReader} (a {@code ContentObserver} on Android's SMS
 * content provider, requiring the READ_SMS/RECEIVE_SMS runtime permissions).
 * Append-only log, same posture as {@link DeviceNotification}/{@link
 * DeviceAppUsageEvent}. Sensitive PII - reachable only through the same
 * device-token-authenticated ingestion path and the same tenant-scoped
 * schema-per-tenant isolation every other row in this module already relies
 * on; no additional encryption-at-rest beyond the database's own, consistent
 * with how {@link DeviceNotification#getText()} and channel message logs
 * elsewhere in this platform are already stored.
 */
@Entity
@Table(name = "device_sms_message")
public class DeviceSmsMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(length = 64)
    private String address;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DeviceSmsDirection direction;

    @Column(name = "sms_timestamp")
    private Instant smsTimestamp;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected DeviceSmsMessage() {
        // JPA
    }

    public DeviceSmsMessage(UUID deviceId, String address, String body, DeviceSmsDirection direction, Instant smsTimestamp) {
        this.deviceId = deviceId;
        this.address = address;
        this.body = body;
        this.direction = direction;
        this.smsTimestamp = smsTimestamp;
        this.receivedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public String getAddress() {
        return address;
    }

    public String getBody() {
        return body;
    }

    public DeviceSmsDirection getDirection() {
        return direction;
    }

    public Instant getSmsTimestamp() {
        return smsTimestamp;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
