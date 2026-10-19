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
 * A short-lived, single-use 6-digit code an authenticated tenant member
 * generates from the console (see {@code DeviceController#generatePairingCode})
 * and types into the Mobile SDK app during first-run setup - the one step in
 * this whole device-pairing flow that has to happen before a device has any
 * credential of its own, so it can't yet be resolved via {@code
 * ExternalAccountProvider}/{@code ChannelAccountLink} the way every later
 * device request is (see {@code DeviceGatewayController}'s javadoc).
 *
 * <p>Lives in {@code control_plane} for the identical structural reason
 * {@link com.agenticai.core.tenancy.ChannelAccountLink} does: "which tenant
 * does this code belong to" is a cross-tenant question by definition, asked
 * by a request (the device's claim call) that has no JWT and therefore no
 * schema to run a tenant-scoped query against yet.
 *
 * <p>No database uniqueness constraint on {@code code} itself (unlike {@code
 * ChannelAccountLink}'s permanent provider+external-id uniqueness) - a
 * 6-digit code is a small, constantly-recycling space (900,000 values) and a
 * collision only matters while an earlier code with the same digits is still
 * un-expired and unconsumed; {@code DevicePairingService#generateCode}
 * checks for that narrower condition and retries instead.
 */
@Entity
@Table(name = "device_pairing_code", schema = "control_plane")
public class DevicePairingCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 6)
    private String code;

    @Column(name = "tenant_schema", nullable = false, length = 63)
    private String tenantSchema;

    @Column(name = "created_by", length = 190)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean consumed;

    protected DevicePairingCode() {
        // JPA
    }

    public DevicePairingCode(String code, String tenantSchema, String createdBy, Instant expiresAt) {
        this.code = code;
        this.tenantSchema = tenantSchema;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.consumed = false;
    }

    public void consume() {
        this.consumed = true;
    }

    public boolean isUsable(Instant now) {
        return !consumed && now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getTenantSchema() {
        return tenantSchema;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isConsumed() {
        return consumed;
    }
}
