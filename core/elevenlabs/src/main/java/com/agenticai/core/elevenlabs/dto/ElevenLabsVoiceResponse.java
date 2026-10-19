package com.agenticai.core.elevenlabs.dto;

import com.agenticai.core.elevenlabs.ElevenLabsVoice;
import java.time.Instant;
import java.util.UUID;

public record ElevenLabsVoiceResponse(
    UUID id,
    String elevenLabsVoiceId,
    String name,
    Instant createdAt
) {
    public static ElevenLabsVoiceResponse from(ElevenLabsVoice voice) {
        return new ElevenLabsVoiceResponse(
            voice.getId(),
            voice.getElevenLabsVoiceId(),
            voice.getName(),
            voice.getCreatedAt()
        );
    }
}
