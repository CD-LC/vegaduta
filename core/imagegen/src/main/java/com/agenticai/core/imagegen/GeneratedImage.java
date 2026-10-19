package com.agenticai.core.imagegen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A single OpenAI Images API generation result, persisted so {@code
 * GeneratedImageController} can serve the bytes back through this app's own
 * authenticated endpoint rather than returning a raw/signed object-storage
 * URL - see {@code GenerateImageToolCallback}'s javadoc for why a tool
 * result can never carry raw binary directly.
 *
 * <p>Mirrors {@code com.agenticai.core.sarvam.SarvamGeneratedAudio}'s shape
 * exactly, minus the legacy base64 column that class carries for backward
 * compatibility with pre-storage-migration rows - this table was created
 * after Storage Stage 3, so every row always has an {@link #objectKey}.
 */
@Entity
@Table(name = "generated_image")
public class GeneratedImage {

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

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "size")
    private String size;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GeneratedImage() {
        // JPA
    }

    private GeneratedImage(
        String filename, String contentType, String objectKey,
        String prompt, String model, String size, String provider, Instant createdAt
    ) {
        this.filename = filename;
        this.contentType = contentType;
        this.objectKey = objectKey;
        this.prompt = prompt;
        this.model = model;
        this.size = size;
        this.provider = provider;
        this.createdAt = createdAt;
    }

    public static GeneratedImage create(
        String filename, String contentType, String objectKey,
        String prompt, String model, String size, String provider
    ) {
        return new GeneratedImage(filename, contentType, objectKey, prompt, model, size, provider, Instant.now());
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

    public String getSize() {
        return size;
    }

    public String getProvider() {
        return provider;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
