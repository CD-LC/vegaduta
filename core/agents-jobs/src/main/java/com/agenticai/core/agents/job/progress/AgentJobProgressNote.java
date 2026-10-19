package com.agenticai.core.agents.job.progress;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One entry in a job's progress trail. See {@code
 * db/migration/tenant/V209__agent_job_output_and_progress.sql} for the
 * column-by-column rationale (why there is no sequence column, why {@code
 * percent} is nullable, why this FKs to {@code agent_job} when {@code agent_job}
 * itself deliberately does not FK to {@code agent}).
 *
 * <p><b>Append-only. There are no setters and no update path anywhere.</b> A
 * progress trail whose entries can be rewritten is not a trail - the whole value
 * of the table is that reading it later tells you what was true at the time,
 * including the note that was wrong. Corrections are new rows.
 *
 * <p>Deliberately holds {@code jobId} as a plain {@link UUID} rather than a
 * {@code @ManyToOne} to {@code AgentJob}. These rows are written from inside a
 * running turn, one at a time, and are read as a flat list; a managed
 * association would drag the whole job entity (goal, result, trace) into the
 * persistence context on every note for no benefit, and would make the
 * best-effort write contract in {@link AgentJobProgressService} harder to honour
 * because a stale association would start throwing where a bare id cannot.
 */
@Entity
@Table(name = "agent_job_progress")
public class AgentJobProgressNote {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "note", nullable = false, length = 500)
    private String note;

    /**
     * Nullable, and null is the common case - only set when the agent actually
     * supplied a number. The runtime never invents one: a synthetic percentage
     * derived from turn count would look authoritative while being meaningless,
     * which is worse for an operator deciding whether to cancel than no number.
     */
    @Column(name = "percent")
    private Short percent;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private ProgressSource source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AgentJobProgressNote() {
        // JPA
    }

    /** The only way to build one - see the class javadoc on append-only. */
    public static AgentJobProgressNote of(UUID id, UUID jobId, String note, Short percent, ProgressSource source, Instant createdAt) {
        AgentJobProgressNote entry = new AgentJobProgressNote();
        entry.id = id;
        entry.jobId = jobId;
        entry.note = note;
        entry.percent = percent;
        entry.source = source;
        entry.createdAt = createdAt;
        return entry;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public String getNote() {
        return note;
    }

    public Short getPercent() {
        return percent;
    }

    public ProgressSource getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
