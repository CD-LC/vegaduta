package com.agenticai.core.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for the sidecar's POST /extract - field names match its
 * Pydantic model exactly. {@code ocrProvider}/{@code ocrApiKey} are optional
 * (null unless the tenant has activated a cloud OCR provider via {@code
 * OcrProviderConfigService}) - the sidecar's {@code ExtractRequest} treats
 * both as {@code Optional[str] = None} and falls back to Tesseract when
 * either is missing, exactly like an unconfigured tenant today.
 */
public record SidecarExtractRequest(
    String filename,
    @JsonProperty("content_base64") String contentBase64,
    @JsonProperty("ocr_provider") String ocrProvider,
    @JsonProperty("ocr_api_key") String ocrApiKey
) {
    public SidecarExtractRequest(String filename, String contentBase64) {
        this(filename, contentBase64, null, null);
    }
}
