package com.agenticai.core.agents.learning.consolidation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One nightly consolidation run of one person's facts (migration V683). Holds counts of what the
 * run planned or did, never memory text - the report of a dry run.
 */
@Entity
@Table(name = "memory_consolidation_runs")
@IdClass(MemoryConsolidationRun.Key.class)
public class MemoryConsolidationRun {

    @Id
    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Id
    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    /** {@code dry_run} (planned only) or {@code apply} (tombstones written). */
    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "rows_seen", nullable = false)
    private int rowsSeen;

    @Column(name = "forgets", nullable = false)
    private int forgets;

    @Column(name = "merges", nullable = false)
    private int merges;

    @Column(name = "summaries", nullable = false)
    private int summaries;

    @Column(name = "deferred", nullable = false)
    private int deferred;

    @Column(name = "tombstoned", nullable = false)
    private int tombstoned;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected MemoryConsolidationRun() {}

    public static class Key implements Serializable {
        private String subject;
        private LocalDate runDate;

        public Key() {}

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(subject, k.subject) && Objects.equals(runDate, k.runDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(subject, runDate);
        }
    }
}
