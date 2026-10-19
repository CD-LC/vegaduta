package com.agenticai.core.knowledge.ocr.dto;

import com.agenticai.core.knowledge.ocr.OcrProvider;

/** Never carries the API key - only which provider (if any) is currently active for this tenant. */
public record OcrProviderStatusResponse(OcrProvider provider) {
}
