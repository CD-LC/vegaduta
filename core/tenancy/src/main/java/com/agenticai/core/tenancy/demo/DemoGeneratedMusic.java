package com.agenticai.core.tenancy.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One music clip an anonymous sandbox visitor generated (migration V263) -
 * same control-plane capability-URL shape as {@link DemoGeneratedImage}, see
 * that class's javadoc for the full "why control_plane, why a random UUID id"
 * reasoning; it applies here unchanged, audio instead of image.
 */
@Entity
@Table(name = "demo_generated_music", schema = "control_plane")
public class DemoGeneratedMusic {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "object_key", nullable = false, length = 512, updatable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false, length = 128, updatable = false)
    private String contentType;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String prompt;

    @Column(name = "model", nullable = false, length = 128, updatable = false)
    private String model;

    @Column(name = "duration_seconds", nullable = false, updatable = false)
    private int durationSeconds;

    @Column(name = "created_ip", length = 45, updatable = false)
    private String createdIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DemoGeneratedMusic() {
        // JPA
    }

    public DemoGeneratedMusic(
        UUID sessionId, String objectKey, String contentType, String prompt, String model, int durationSeconds, String createdIp
    ) {
        this.sessionId = sessionId;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.prompt = prompt;
        this.model = model;
        this.durationSeconds = durationSeconds;
        this.createdIp = createdIp;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getModel() {
        return model;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public String getCreatedIp() {
        return createdIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
