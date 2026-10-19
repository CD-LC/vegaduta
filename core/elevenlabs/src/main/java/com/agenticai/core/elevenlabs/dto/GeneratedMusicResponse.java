package com.agenticai.core.elevenlabs.dto;

import com.agenticai.core.elevenlabs.GeneratedMusic;
import java.time.Instant;
import java.util.UUID;

public record GeneratedMusicResponse(
    UUID id, String filename, String contentType, String prompt, String model, int durationSeconds, Instant createdAt
) {
    public static GeneratedMusicResponse from(GeneratedMusic track) {
        return new GeneratedMusicResponse(
            track.getId(), track.getFilename(), track.getContentType(), track.getPrompt(),
            track.getModel(), track.getDurationSeconds(), track.getCreatedAt());
    }
}
