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
 * A tenant-cloned ElevenLabs voice - the result of uploading a sample clip
 * via {@code ElevenLabsVoiceService#cloneVoice}, which calls ElevenLabs'
 * {@code POST /v1/voices/add} and persists the {@code voice_id} it returns
 * here. {@link #elevenLabsVoiceId} is the only thing {@link
 * ElevenLabsClient#textToSpeech} actually needs to speak in this voice - the
 * sample audio itself is not kept (ElevenLabs owns and stores the trained
 * voice model on their side; there is nothing useful to re-upload).
 *
 * <p>No {@code tenant_id} column, same as every other table in this module -
 * schema-per-tenant multi-tenancy already isolates one tenant's cloned
 * voices from another's.
 */
@Entity
@Table(name = "elevenlabs_voice")
public class ElevenLabsVoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "elevenlabs_voice_id", nullable = false, length = 128)
    private String elevenLabsVoiceId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ElevenLabsVoice() {
        // JPA
    }

    private ElevenLabsVoice(String elevenLabsVoiceId, String name, Instant createdAt) {
        this.elevenLabsVoiceId = elevenLabsVoiceId;
        this.name = name;
        this.createdAt = createdAt;
    }

    public static ElevenLabsVoice create(String elevenLabsVoiceId, String name) {
        return new ElevenLabsVoice(elevenLabsVoiceId, name, Instant.now());
    }

    public UUID getId() {
        return id;
    }

    public String getElevenLabsVoiceId() {
        return elevenLabsVoiceId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
