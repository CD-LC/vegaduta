package com.agenticai.core.brief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One solution ({@code brief} table, tenant schema, migration V341). Deliberately a plain entity
 * with no schema override - schema-per-tenant scopes it, same as {@code Agent}. The manifest itself
 * lives in {@link BriefVersionEntity}; this row carries the lifecycle (status, current version,
 * trust rung). Status and rung are strings guarded by CHECK constraints in the migration.
 */
@Entity
@Table(name = "brief")
public class BriefEntity {

    public static final String DRAFT = "DRAFT";
    public static final String APPLIED = "APPLIED";
    public static final String FAILED = "FAILED";
    public static final String ARCHIVED = "ARCHIVED";

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String goal;

    @Column(nullable = false, length = 16)
    private String locale;

    @Column(nullable = false, length = 16)
    private String status = DRAFT;

    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Column(nullable = false, length = 16)
    private String rung = "UNDER_WATCH";

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BriefEntity() {
        // JPA
    }

    public BriefEntity(UUID id, String title, String goal, String locale, String rung, String createdBy) {
        this.id = id;
        this.title = title;
        this.goal = goal;
        this.locale = locale;
        this.rung = rung;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void touch(String title, String goal, String locale, int currentVersion) {
        this.title = title;
        this.goal = goal;
        this.locale = locale;
        this.currentVersion = currentVersion;
        this.updatedAt = Instant.now();
    }

    public void setStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void setRung(String rung) {
        this.rung = rung;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getGoal() {
        return goal;
    }

    public String getLocale() {
        return locale;
    }

    public String getStatus() {
        return status;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public String getRung() {
        return rung;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
