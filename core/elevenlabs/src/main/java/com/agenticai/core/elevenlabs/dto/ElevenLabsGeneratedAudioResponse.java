package com.agenticai.core.elevenlabs.dto;

import com.agenticai.core.elevenlabs.ElevenLabsGeneratedAudio;
import java.time.Instant;
import java.util.UUID;

/** Row shape for the Files page's "Generated speech (ElevenLabs)" listing - never carries the raw bytes, only what {@code GET /api/elevenlabs/audio/{id}} needs to fetch them. */
public record ElevenLabsGeneratedAudioResponse(
    UUID id,
    String filename,
    String contentType,
    String voiceId,
    Instant createdAt,
    String downloadUrl
) {
    public static ElevenLabsGeneratedAudioResponse from(ElevenLabsGeneratedAudio audio) {
        return new ElevenLabsGeneratedAudioResponse(
            audio.getId(),
            audio.getFilename(),
            audio.getContentType(),
            audio.getVoiceId(),
            audio.getCreatedAt(),
            "/api/elevenlabs/audio/" + audio.getId()
        );
    }
}
