package com.agenticai.core.common.studioapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A non-owner member of a {@link StudioProject} (see V372). */
@Entity
@Table(name = "studio_project_member")
public class StudioProjectMember {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "project_id", nullable = false, length = 64)
    private String projectId;

    @Column(name = "member_subject", nullable = false, length = 128)
    private String memberSubject;

    /** VIEWER or EDITOR. */
    @Column(name = "role", nullable = false, length = 16)
    private String role;

    @Column(name = "invited_by", nullable = false, length = 128)
    private String invitedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getMemberSubject() { return memberSubject; }
    public void setMemberSubject(String memberSubject) { this.memberSubject = memberSubject; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getInvitedBy() { return invitedBy; }
    public void setInvitedBy(String invitedBy) { this.invitedBy = invitedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
