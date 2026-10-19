package com.agenticai.core.common.studioapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** One undo point of a {@link StudioProject}: the whole single-file app at a moment. */
@Entity
@Table(name = "studio_project_version")
public class StudioProjectVersion {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    /** Client timestamp (epoch ms); with {@code projectId} the natural key. */
    @Column(name = "at_ms", nullable = false)
    private long atMs;

    @Column(name = "note", length = 400)
    private String note;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "html", nullable = false, columnDefinition = "TEXT")
    private String html;

    /** Keycloak subject of whoever saved this version; null for pre-collaboration rows. */
    @Column(name = "author_subject", length = 128)
    private String authorSubject;

    public String getAuthorSubject() { return authorSubject; }
    public void setAuthorSubject(String authorSubject) { this.authorSubject = authorSubject; }
    public UUID getId() { return id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public long getAtMs() { return atMs; }
    public void setAtMs(long atMs) { this.atMs = atMs; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getHtml() { return html; }
    public void setHtml(String html) { this.html = html; }
}
