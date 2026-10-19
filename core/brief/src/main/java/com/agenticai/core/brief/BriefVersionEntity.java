package com.agenticai.core.brief;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An immutable manifest snapshot ({@code brief_version}). {@code manifestJson} is a
 * {@code brief-schema.v1.json} instance kept as a JSON string mapped to jsonb (the same
 * String + {@code @JdbcTypeCode(SqlTypes.JSON)} pattern {@code ProcedureRule} uses).
 */
@Entity
@Table(name = "brief_version", uniqueConstraints = @UniqueConstraint(columnNames = {"brief_id", "version"}))
public class BriefVersionEntity {

    @Id
    private UUID id;

    @Column(name = "brief_id", nullable = false)
    private UUID briefId;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_json", nullable = false, columnDefinition = "jsonb")
    private String manifestJson;

    @Column(name = "parent_version")
    private Integer parentVersion;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BriefVersionEntity() {
        // JPA
    }

    public BriefVersionEntity(
        UUID briefId, int version, String manifestJson, Integer parentVersion, String note, String createdBy
    ) {
        this.id = UUID.randomUUID();
        this.briefId = briefId;
        this.version = version;
        this.manifestJson = manifestJson;
        this.parentVersion = parentVersion;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getBriefId() {
        return briefId;
    }

    public int getVersion() {
        return version;
    }

    public String getManifestJson() {
        return manifestJson;
    }

    public Integer getParentVersion() {
        return parentVersion;
    }

    public String getNote() {
        return note;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
