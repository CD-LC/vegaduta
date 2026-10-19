package com.agenticai.core.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Master Roadmap 2026 item #13 (A/B testing & feature flags, §B). One
 * platform-admin-managed flag definition - {@code key} is the stable
 * identifier call sites reference (never the row id, which can't be
 * hand-typed into config), {@code rolloutPercentage} (0-100) is what
 * fraction of buckets {@link FeatureFlagService#evaluate} assigns to the
 * "treatment" variant. Same control-plane, cross-tenant scope as {@link
 * PlatformKillSwitch}/{@link PlatformSetting} - a flag is a platform-wide
 * concept, not a per-tenant one; a rollout percentage IS the mechanism for
 * gradually exposing tenants to a change, so there's no separate per-tenant
 * override needed on top of it.
 *
 * <p>{@code enabled = false} is a hard master switch distinct from {@code
 * rolloutPercentage = 0} - both currently produce the same runtime effect
 * (nobody gets "treatment"), but they mean different things to whoever's
 * looking at the admin list: 0% is "ramping up, currently at the floor,"
 * disabled is "not running right now, ignore the percentage entirely." Kept
 * as two fields rather than collapsing enabled into "percentage 0" for that
 * legibility, mirroring why {@link PlatformKillSwitch} keeps {@code stopped}
 * and {@code reason} as separate fields rather than overloading one.
 */
@Entity
@Table(name = "feature_flag", schema = "control_plane")
public class FeatureFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "flag_key", nullable = false, unique = true, length = 120)
    private String key;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "rollout_percentage", nullable = false)
    private int rolloutPercentage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected FeatureFlag() {
        // JPA
    }

    public FeatureFlag(String key, String description, boolean enabled, int rolloutPercentage, String updatedBy) {
        this.key = key;
        this.description = description;
        this.enabled = enabled;
        this.rolloutPercentage = clampPercentage(rolloutPercentage);
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.updatedBy = updatedBy;
    }

    public void update(String description, boolean enabled, int rolloutPercentage, String updatedBy) {
        this.description = description;
        this.enabled = enabled;
        this.rolloutPercentage = clampPercentage(rolloutPercentage);
        this.updatedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    private static int clampPercentage(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public UUID getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getDescription() {
        return description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getRolloutPercentage() {
        return rolloutPercentage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
