package com.agenticai.core.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * #815 Governance follow-up (Fail-safe: platform + per-tenant kill switch) -
 * singleton row, always {@code id = 1L}, same convention as {@code
 * com.agenticai.core.providers.HostedLlmPoolSettings} (see that class's
 * javadoc for the precedent this mirrors). When {@link #stopped} is {@code
 * true}, {@link TenantStatusGuardImpl#isUsable} returns {@code false} for
 * EVERY tenant regardless of that tenant's own status/{@code
 * emergencyStopped} flag - the platform-wide incident-response lever,
 * distinct from {@link Tenant#emergencyStop} which only ever affects one
 * tenant.
 */
@Entity
@Table(name = "platform_kill_switch", schema = "control_plane")
public class PlatformKillSwitch {

    /** Fixed singleton id - see this class's javadoc. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(nullable = false)
    private boolean stopped;

    @Column(length = 500)
    private String reason;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected PlatformKillSwitch() {
        // JPA
    }

    public void stop(String reason, String updatedBy) {
        this.stopped = true;
        this.reason = reason;
        this.stoppedAt = Instant.now();
        this.updatedBy = updatedBy;
    }

    public void resume(String updatedBy) {
        this.stopped = false;
        this.reason = null;
        this.stoppedAt = null;
        this.updatedBy = updatedBy;
    }

    public Long getId() {
        return id;
    }

    public boolean isStopped() {
        return stopped;
    }

    public String getReason() {
        return reason;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}
