package com.agenticai.core.sarvam.dto;

import com.agenticai.core.sarvam.SarvamGeneratedAudio;
import java.time.Instant;
import java.util.UUID;

/** Row shape for the Files page's "Generated speech (Sarvam)" listing - never carries the raw bytes, only what {@code GET /api/sarvam/audio/{id}} needs to fetch them (works for both object-storage and legacy base64 rows, same as that endpoint). */
public record SarvamGeneratedAudioResponse(
    UUID id,
    String filename,
    String contentType,
    Instant createdAt,
    String downloadUrl
) {
    public static SarvamGeneratedAudioResponse from(SarvamGeneratedAudio audio) {
        return new SarvamGeneratedAudioResponse(
            audio.getId(),
            audio.getFilename(),
            audio.getContentType(),
            audio.getCreatedAt(),
            "/api/sarvam/audio/" + audio.getId()
        );
    }
}
