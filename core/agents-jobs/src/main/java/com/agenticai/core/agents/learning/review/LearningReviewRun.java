package com.agenticai.core.agents.learning.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** The fact that the daily memory review looked at one person on one day (migration V643). */
@Entity
@Table(name = "learning_review_runs")
@IdClass(LearningReviewRun.Key.class)
public class LearningReviewRun {

    @Id
    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Id
    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Column(name = "filed", nullable = false)
    private int filed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LearningReviewRun() {}

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
