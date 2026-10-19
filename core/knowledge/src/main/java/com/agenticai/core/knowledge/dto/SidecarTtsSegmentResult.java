package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One narration segment synthesized by the sidecar's {@code POST /tts}.
 *
 * <p>{@code durationSec} is the load-bearing field and the reason this
 * endpoint returns per-segment results at all: it is MEASURED from the
 * returned WAV's own frame count, not estimated. The video pipeline's
 * render aligns each B-roll clip against this number, so an estimate here
 * would put A/V drift into every episode.
 */
public record SidecarTtsSegmentResult(
    String id,
    @JsonProperty("duration_sec") double durationSec,
    @JsonProperty("sample_rate") int sampleRate,
    @JsonProperty("audio_base64") String audioBase64
) {
}
