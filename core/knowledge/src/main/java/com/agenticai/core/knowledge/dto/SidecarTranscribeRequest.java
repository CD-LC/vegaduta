package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Request body for the sidecar's POST /transcribe - field names match its Pydantic model exactly. */
public record SidecarTranscribeRequest(
    String filename,
    @JsonProperty("content_base64") String contentBase64
) {
}
