package com.agenticai.core.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Platform-admin alert threshold follow-up (#956) - singleton row, always
 * {@code id = 1L}, same convention as {@code
 * com.agenticai.core.tenancy.PlatformKillSwitch}/{@code
 * com.agenticai.core.providers.HostedLlmPoolSettings} (see either's javadoc
 * for the precedent this mirrors). Lives in {@code core/platform} rather
 * than {@code core/tenancy} because nothing outside this module's own
 * {@link PlatformObservabilityService}/{@code PlatformController} needs it -
 * unlike the kill switch, which {@code TenantStatusGuardImpl} (core/tenancy)
 * must read on every request.
 *
 * <p>Deliberately just ONE threshold today (fallback failures, summed across
 * every active tenant's agents, cumulative/lifetime - see {@link
 * PlatformObservabilityService#computeFallbackAlertStatus}), not a generic
 * multi-metric alert-rule engine: that would be building speculative
 * infrastructure for alert types that don't exist yet, the same "don't
 * invent it before there's a second consumer" posture
 * {@code PlatformObservabilityService}'s own class javadoc already states
 * for its LLM-usage metric. A second threshold can be added as a second
 * column here (or a genuinely separate settings row) once a second real use
 * case exists.
 */
@Entity
@Table(name = "platform_alert_settings", schema = "control_plane")
public class PlatformAlertSettings {

    /** Fixed singleton id - see this class's javadoc. */
    public static final long SINGLETON_ID = 1L;

    /** Migration V84 seeds the singleton row with this value - a small but non-zero starting point. */
    public static final int DEFAULT_FALLBACK_FAILURE_THRESHOLD = 10;

    @Id
    private Long id;

    @Column(name = "fallback_failure_threshold", nullable = false)
    private int fallbackFailureThreshold = DEFAULT_FALLBACK_FAILURE_THRESHOLD;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected PlatformAlertSettings() {
        // JPA
    }

    /** Used only by {@link PlatformObservabilityService} to create the singleton row on first access - migration V84 already seeds it, so this is a defensive fallback, not the normal path. */
    public PlatformAlertSettings(int fallbackFailureThreshold) {
        this.id = SINGLETON_ID;
        this.fallbackFailureThreshold = fallbackFailureThreshold;
        this.updatedAt = Instant.now();
    }

    public void setFallbackFailureThreshold(int fallbackFailureThreshold, String updatedBy) {
        this.fallbackFailureThreshold = fallbackFailureThreshold;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public int getFallbackFailureThreshold() {
        return fallbackFailureThreshold;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
