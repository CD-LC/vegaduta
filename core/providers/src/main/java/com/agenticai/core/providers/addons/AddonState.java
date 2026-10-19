package com.agenticai.core.providers.addons;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * What a person asked for ({@code desired}) and the last thing the host reported, for one add-on.
 * One row per add-on that was ever touched; no row means "the catalog default, or whatever core can
 * see running" (see {@link AddonStateRules}).
 *
 * <p>Lives in {@code control_plane}, not in a tenant schema: an add-on is a container or a model on
 * the machine the install runs on, shared by everyone who uses it, and the host-side installer that
 * applies it has no tenant. Same reasoning as {@link com.agenticai.core.providers.PlatformHostedLlmProvider}.
 *
 * <p>{@code rev} counts changes of the wish. A host report names the {@code rev} it acted on
 * ({@code reportedRev}); a report for an older {@code rev} says nothing about the current wish, so
 * switching an add-on back and forth can never be mistaken for it having been applied.
 */
@Entity
@Table(name = "addon_state", schema = "control_plane")
public class AddonState {

    /** Values of {@code reportedStatus}. */
    public static final String RUNNING = "running";

    public static final String STOPPED = "stopped";
    public static final String APPLYING = "applying";
    public static final String FAILED = "failed";

    @Id
    @Column(name = "addon_id", length = 64)
    private String addonId;

    @Column(name = "desired", nullable = false)
    private boolean desired;

    @Column(name = "rev", nullable = false)
    private long rev;

    @Column(name = "desired_at", nullable = false)
    private Instant desiredAt;

    @Column(name = "desired_by", length = 255)
    private String desiredBy;

    @Column(name = "reported_status", length = 16)
    private String reportedStatus;

    @Column(name = "reported_rev")
    private Long reportedRev;

    @Column(name = "reported_message", length = 500)
    private String reportedMessage;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    protected AddonState() {
        // JPA
    }

    AddonState(String addonId, boolean desired, long rev, String desiredBy) {
        this.addonId = addonId;
        this.desired = desired;
        this.rev = rev;
        this.desiredAt = Instant.now();
        this.desiredBy = desiredBy;
    }

    public String getAddonId() {
        return addonId;
    }

    public boolean isDesired() {
        return desired;
    }

    public long getRev() {
        return rev;
    }

    public Instant getDesiredAt() {
        return desiredAt;
    }

    public String getReportedStatus() {
        return reportedStatus;
    }

    public Long getReportedRev() {
        return reportedRev;
    }

    public String getReportedMessage() {
        return reportedMessage;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    /** True when the stored report is about the current wish, not an earlier one. */
    boolean reportIsCurrent() {
        return reportedStatus != null && reportedRev != null && reportedRev == rev;
    }

    void change(boolean newDesired, String by) {
        this.desired = newDesired;
        this.rev = rev + 1;
        this.desiredAt = Instant.now();
        this.desiredBy = by;
    }

    void report(String status, long forRev, String message) {
        this.reportedStatus = status;
        this.reportedRev = forRev;
        this.reportedMessage = message;
        this.reportedAt = Instant.now();
    }
}
