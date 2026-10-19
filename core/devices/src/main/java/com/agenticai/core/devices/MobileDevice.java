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
 * A phone paired to this tenant via {@code DevicePairingService#claim} - an
 * ordinary tenant-scoped entity (automatic schema-per-tenant isolation, same
 * as {@code WorkflowDefinition}/{@code ChannelSession}, no manual tenant
 * filtering needed on any query here).
 *
 * <p>{@code deviceToken} is the credential a paired device presents on every
 * subsequent request (an {@code X-Device-Token} header) since a phone has no
 * Keycloak session of its own - generated once at claim time (24 random
 * bytes, hex-encoded, via the same {@code SecureRandom}+{@code HexFormat}
 * approach {@code WorkflowService#generateToken} already uses for webhook
 * tokens) and never rotated automatically; revoking the device is how a
 * tenant invalidates it. It is also registered, verbatim, as the {@code
 * external_account_id} half of a {@code control_plane.channel_account_link}
 * row (provider {@code DEVICE}) at claim time - see {@code
 * DevicePairingService#claim} - which is what lets {@code
 * DeviceGatewayController}'s poll/result endpoints resolve a tenant schema
 * from nothing but this token, before any tenant-scoped query is possible.
 */
@Entity
@Table(name = "mobile_device")
public class MobileDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "device_name", nullable = false, length = 120)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DevicePlatform platform;

    @Column(name = "device_token", nullable = false, unique = true, length = 64)
    private String deviceToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(nullable = false)
    private boolean revoked;

    /** Comma-separated capability keys the client last declared (e.g. {@code camera.photo,location.get}); empty = unknown. */
    @Column(length = 512)
    private String capabilities;

    protected MobileDevice() {
        // JPA
    }

    public MobileDevice(String deviceName, DevicePlatform platform, String deviceToken) {
        this.deviceName = deviceName;
        this.platform = platform;
        this.deviceToken = deviceToken;
        this.createdAt = Instant.now();
        this.revoked = false;
    }

    public void touch() {
        this.lastSeenAt = Instant.now();
    }

    public void revoke() {
        this.revoked = true;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public UUID getId() {
        return id;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public DevicePlatform getPlatform() {
        return platform;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public String getCapabilities() {
        return capabilities;
    }
}
