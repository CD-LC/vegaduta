package com.agenticai.core.voice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/voice/agents/{agentId}/calls/{roomName}/recording/start}
 * and {@code .../stop} response - enough to show recording state in a
 * future console UI ("this call is being recorded") without exposing the
 * raw S3 object key (an internal storage detail, not something a frontend
 * needs to construct a URL from directly - playback should go through a
 * dedicated, authorization-checked download endpoint once one exists).
 */
public record VoiceCallRecordingResponse(
    UUID id,
    String roomName,
    String status,
    Instant startedAt,
    Instant endedAt
) {
}
