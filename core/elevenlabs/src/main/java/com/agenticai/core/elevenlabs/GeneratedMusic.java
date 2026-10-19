package com.agenticai.core.elevenlabs;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A music track composed by {@link ElevenLabsClient#composeMusic} via the
 * {@code generate_music} agent tool, stored so the tool can hand the calling
 * LLM a retrievable reference instead of raw audio - same shape and reasoning
 * as {@link ElevenLabsGeneratedAudio}/{@code GeneratedImage}. Its own table
 * rather than a row in {@code elevenlabs_generated_audio} because that table's
 * row count IS the platform-voice quota ledger (see {@link
 * ElevenLabsGeneratedAudioService#countSince}); music is billed per minute,
 * not per character, and must not eat the speech allowance.
 */
@Entity
@Table(name = "generated_music")
public class GeneratedMusic {

    /** {@link #getBilledTo()} value when the tenant's own ElevenLabs key paid. */
    public static final String BILLED_TO_TENANT = "TENANT";
    /** {@link #getBilledTo()} value when VegaDūta's platform key paid - these rows are the platform-spend quota ledger. */
    public static final String BILLED_TO_PLATFORM = "PLATFORM";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "model", nullable = false, length = 128)
    private String model;

    @Column(name = "provider", nullable = false, length = 64)
    private String provider;

    @Column(name = "billed_to", nullable = false, length = 16)
    private String billedTo;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GeneratedMusic() {
        // JPA
    }

    private GeneratedMusic(
        String filename, String contentType, String objectKey, String prompt, String model,
        String provider, String billedTo, int durationSeconds, Instant createdAt
    ) {
        this.filename = filename;
        this.contentType = contentType;
        this.objectKey = objectKey;
        this.prompt = prompt;
        this.model = model;
        this.provider = provider;
        this.billedTo = billedTo;
        this.durationSeconds = durationSeconds;
        this.createdAt = createdAt;
    }

    public static GeneratedMusic create(
        String filename, String contentType, String objectKey, String prompt, String model,
        String provider, String billedTo, int durationSeconds
    ) {
        return new GeneratedMusic(filename, contentType, objectKey, prompt, model, provider, billedTo, durationSeconds, Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public String getContentType() {
        return contentType;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getModel() {
        return model;
    }

    public String getProvider() {
        return provider;
    }

    public String getBilledTo() {
        return billedTo;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
