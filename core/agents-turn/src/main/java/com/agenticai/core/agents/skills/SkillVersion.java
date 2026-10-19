package com.agenticai.core.agents.skills;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A snapshot of one custom skill version that went live (migration V590). Append-only: the live
 * content stays on {@link Skill}; this is the history a reviewer reads to see what an edit of an
 * approved skill changed. Tenant-schema table, no tenant_id.
 */
@Entity
@Table(name = "skill_version")
public class SkillVersion {

    @Id private UUID id;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 1024)
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /** JSON object {path: content}; null when the skill has no bundled files. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "files", columnDefinition = "jsonb")
    private String filesJson;

    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(length = 255)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SkillVersion() {}

    SkillVersion(
            UUID skillId,
            int version,
            String description,
            String body,
            String filesJson,
            String contentSha256,
            String createdBy,
            String note) {
        this.id = UUID.randomUUID();
        this.skillId = skillId;
        this.version = version;
        this.description = description;
        this.body = body;
        this.filesJson = filesJson;
        this.contentSha256 = contentSha256;
        this.createdBy = createdBy;
        this.note = note == null || note.length() <= 255 ? note : note.substring(0, 255);
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public int getVersion() {
        return version;
    }

    public String getDescription() {
        return description;
    }

    public String getBody() {
        return body;
    }

    public String getFilesJson() {
        return filesJson;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
