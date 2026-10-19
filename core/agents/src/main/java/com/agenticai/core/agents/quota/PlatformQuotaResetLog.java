package com.agenticai.core.agents.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Idempotency/audit row for {@link DailyQuotaResetScheduler} (issue 8) - one
 * row per (job_name, reset_date), enforced by a DB unique constraint (V189),
 * not in-memory instance state. This is what makes the daily reset job both
 * idempotent (a duplicate scheduler tick or a redundant startup catch-up
 * attempt fails to insert a second row for the same day and is treated as a
 * no-op) and safe to run from every horizontally-scaled {@code core}
 * replica (whichever instance's insert wins the unique constraint is the one
 * that actually performs that day's reset action; every other replica's
 * attempt fails fast on the same constraint and skips).
 */
@Entity
@Table(name = "platform_quota_reset_log", schema = "control_plane")
public class PlatformQuotaResetLog {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "job_name", length = 80, nullable = false)
    private String jobName;

    @Column(name = "reset_date", nullable = false)
    private LocalDate resetDate;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "executed_by", length = 255)
    private String executedBy;

    protected PlatformQuotaResetLog() {
        // JPA
    }

    public PlatformQuotaResetLog(String jobName, LocalDate resetDate, Instant executedAt, String executedBy) {
        this.id = UUID.randomUUID();
        this.jobName = jobName;
        this.resetDate = resetDate;
        this.executedAt = executedAt;
        this.executedBy = executedBy;
    }

    public UUID getId() {
        return id;
    }

    public String getJobName() {
        return jobName;
    }

    public LocalDate getResetDate() {
        return resetDate;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public String getExecutedBy() {
        return executedBy;
    }
}
