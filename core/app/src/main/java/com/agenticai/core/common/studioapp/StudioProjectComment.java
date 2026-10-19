package com.agenticai.core.common.studioapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A comment on a {@link StudioProject}, optionally anchored to one version (see V372). */
@Entity
@Table(name = "studio_project_comment")
public class StudioProjectComment {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "version_at_ms")
    private Long versionAtMs;

    @Column(name = "author_subject", nullable = false, length = 128)
    private String authorSubject;

    @Column(name = "author_name", nullable = false, length = 160)
    private String authorName;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "resolved", nullable = false)
    private boolean resolved;

    public UUID getId() { return id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public Long getVersionAtMs() { return versionAtMs; }
    public void setVersionAtMs(Long versionAtMs) { this.versionAtMs = versionAtMs; }
    public String getAuthorSubject() { return authorSubject; }
    public void setAuthorSubject(String authorSubject) { this.authorSubject = authorSubject; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isResolved() { return resolved; }
    public void setResolved(boolean resolved) { this.resolved = resolved; }
}
