package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Response of the sidecar's {@code POST /captions}.
 *
 * <p>{@code words} is returned alongside the finished SRT deliberately, so a
 * caller that wants different cue grouping (a different aspect ratio wants a
 * different line length) can re-group without paying for a second Whisper
 * inference pass.
 */
public record SidecarCaptionsResponse(
    String srt,
    @JsonProperty("cue_count") int cueCount,
    @JsonProperty("word_count") int wordCount,
    List<Map<String, Object>> words
) {
}
