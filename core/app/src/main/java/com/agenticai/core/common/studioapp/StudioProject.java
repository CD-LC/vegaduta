package com.agenticai.core.common.studioapp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A Phone-App-Studio project mirrored server-side so its version history
 * survives a cleared browser or a new device. See V370 for the schema and
 * {@link StudioProjectService} for the sync rules.
 */
@Entity
@Table(name = "studio_project")
public class StudioProject {

    /** Client-generated ("sa-...") id; also the primary key. */
    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "owner_subject", nullable = false, length = 128)
    private String ownerSubject;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "emoji", length = 16)
    private String emoji;

    /** Opaque JSON owned by the studio (connections, auth, ai, publish). */
    @Column(name = "settings", columnDefinition = "TEXT")
    private String settings;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerSubject() { return ownerSubject; }
    public void setOwnerSubject(String ownerSubject) { this.ownerSubject = ownerSubject; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    public String getSettings() { return settings; }
    public void setSettings(String settings) { this.settings = settings; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
