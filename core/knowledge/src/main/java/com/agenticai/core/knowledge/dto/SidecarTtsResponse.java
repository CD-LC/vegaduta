package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Response of the sidecar's {@code POST /tts} (Sarvam narration, per beat). */
public record SidecarTtsResponse(
    String language,
    String model,
    @JsonProperty("sample_rate") int sampleRate,
    @JsonProperty("total_duration_sec") double totalDurationSec,
    List<SidecarTtsSegmentResult> segments,
    @JsonProperty("combined_audio_base64") String combinedAudioBase64
) {
}
