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
 * A synthesized MP3 clip produced by {@link ElevenLabsClient#textToSpeech}
 * via {@code ElevenLabsSpeakToolCallback}, stored so the tool can hand the
 * calling LLM a retrievable reference instead of raw binary audio in the
 * tool result - mirrors {@code SarvamGeneratedAudio}/{@code GeneratedImage}'s
 * shape and reasoning exactly (see those classes' javadoc for why this is
 * its own table per integration rather than one shared table).
 */
@Entity
@Table(name = "elevenlabs_generated_audio")
public class ElevenLabsGeneratedAudio {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "filename", nullable = false)
    private String filename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "object_key", nullable = false, length = 512)
    private String objectKey;

    @Column(name = "voice_id", nullable = false, length = 128)
    private String voiceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ElevenLabsGeneratedAudio() {
        // JPA
    }

    private ElevenLabsGeneratedAudio(String filename, String contentType, String objectKey, String voiceId, Instant createdAt) {
        this.filename = filename;
        this.contentType = contentType;
        this.objectKey = objectKey;
        this.voiceId = voiceId;
        this.createdAt = createdAt;
    }

    public static ElevenLabsGeneratedAudio create(String filename, String contentType, String objectKey, String voiceId) {
        return new ElevenLabsGeneratedAudio(filename, contentType, objectKey, voiceId, Instant.now());
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

    public String getVoiceId() {
        return voiceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
