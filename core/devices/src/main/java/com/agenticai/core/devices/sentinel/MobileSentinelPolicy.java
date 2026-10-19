package com.agenticai.core.devices.sentinel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The tenant-wide Mobile Sentinels policy - a single row pinned to
 * {@code id = 1}, see {@code tenant/V368__mobile_sentinels.sql}. Absent until
 * the first admin PUT; {@link MobileSentinelService} supplies defaults.
 */
@Entity
@Table(name = "mobile_sentinel_policy")
public class MobileSentinelPolicy {

    public static final short SINGLETON_ID = 1;

    @Id
    private Short id;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "sync_enabled", nullable = false)
    private boolean syncEnabled;

    @Column(name = "allow_raw_readings", nullable = false)
    private boolean allowRawReadings;

    /** Comma-separated {@link SentinelKind} names; null = all kinds, empty = none. */
    @Column(name = "allowed_kinds", columnDefinition = "TEXT")
    private String allowedKinds;

    @Column(name = "caregiver_escalation_enabled", nullable = false)
    private boolean caregiverEscalationEnabled;

    @Column(name = "retention_days", nullable = false)
    private int retentionDays;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected MobileSentinelPolicy() {
    }

    public MobileSentinelPolicy(
        boolean enabled, boolean syncEnabled, boolean allowRawReadings, String allowedKinds,
        boolean caregiverEscalationEnabled, int retentionDays
    ) {
        this.id = SINGLETON_ID;
        this.enabled = enabled;
        this.syncEnabled = syncEnabled;
        this.allowRawReadings = allowRawReadings;
        this.allowedKinds = allowedKinds;
        this.caregiverEscalationEnabled = caregiverEscalationEnabled;
        this.retentionDays = retentionDays;
    }

    public void update(
        boolean enabled, boolean syncEnabled, boolean allowRawReadings, String allowedKinds,
        boolean caregiverEscalationEnabled, int retentionDays, String updatedBy
    ) {
        this.enabled = enabled;
        this.syncEnabled = syncEnabled;
        this.allowRawReadings = allowRawReadings;
        this.allowedKinds = allowedKinds;
        this.caregiverEscalationEnabled = caregiverEscalationEnabled;
        this.retentionDays = retentionDays;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public Short getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public boolean isAllowRawReadings() {
        return allowRawReadings;
    }

    public String getAllowedKinds() {
        return allowedKinds;
    }

    public boolean isCaregiverEscalationEnabled() {
        return caregiverEscalationEnabled;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
