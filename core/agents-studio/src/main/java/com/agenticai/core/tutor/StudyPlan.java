package com.agenticai.core.tutor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One member's study plan for one topic (migration V420). Tenant-schema table: the schema is the
 * tenancy, {@code ownerSubject} is the member. The curriculum (modules, lessons, cached lesson
 * content and quizzes) and the spaced-repetition deck are two JSONB documents kept as JSON strings
 * (the same String + {@code @JdbcTypeCode(SqlTypes.JSON)} pattern {@code ProcedureRule} uses);
 * {@link StudyPlanDocs} is the only reader/writer of their shape.
 */
@Entity
@Table(name = "study_plan")
public class StudyPlan {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    private UUID id;

    @Column(name = "owner_subject", nullable = false, length = 255)
    private String ownerSubject;

    @Column(nullable = false, length = 300)
    private String topic;

    @Column(nullable = false, length = 16)
    private String language;

    @Column(nullable = false, length = 32)
    private String level;

    @Column(length = 500)
    private String goal;

    @Column(nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "curriculum", nullable = false, columnDefinition = "jsonb")
    private String curriculumJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "review", nullable = false, columnDefinition = "jsonb")
    private String reviewJson;

    @Column(name = "tutor_agent_id")
    private UUID tutorAgentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Wrapper type on purpose: with an assigned id, Spring Data tells a new row from an existing one
    // by a null version (persist) - a primitive would send every first save through merge().
    @Version
    @Column(nullable = false)
    private Long version;

    protected StudyPlan() {
        // JPA
    }

    public StudyPlan(
        String ownerSubject, String topic, String language, String level, String goal,
        String curriculumJson, String reviewJson, Instant now
    ) {
        this.id = UUID.randomUUID();
        this.ownerSubject = ownerSubject;
        this.topic = topic;
        this.language = language;
        this.level = level;
        this.goal = goal;
        this.status = STATUS_ACTIVE;
        this.curriculumJson = curriculumJson;
        this.reviewJson = reviewJson;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateCurriculum(String curriculumJson, Instant now) {
        this.curriculumJson = curriculumJson;
        this.updatedAt = now;
    }

    public void updateReview(String reviewJson, Instant now) {
        this.reviewJson = reviewJson;
        this.updatedAt = now;
    }

    /** Reminder bookkeeping only - deliberately does not bump {@code updatedAt} (the list sorts on it). */
    public void updateReviewQuietly(String reviewJson) {
        this.reviewJson = reviewJson;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setTutorAgentId(UUID tutorAgentId) {
        this.tutorAgentId = tutorAgentId;
    }

    public UUID getId() { return id; }
    public String getOwnerSubject() { return ownerSubject; }
    public String getTopic() { return topic; }
    public String getLanguage() { return language; }
    public String getLevel() { return level; }
    public String getGoal() { return goal; }
    public String getStatus() { return status; }
    public String getCurriculumJson() { return curriculumJson; }
    public String getReviewJson() { return reviewJson; }
    public UUID getTutorAgentId() { return tutorAgentId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
