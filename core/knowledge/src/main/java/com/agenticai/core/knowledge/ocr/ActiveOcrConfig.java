package com.agenticai.core.knowledge.ocr;

/**
 * The resolved, decrypted form of a tenant's active {@link OcrProviderConfig}
 * - what {@link OcrProviderConfigService#resolveActive} hands to {@code
 * SidecarClient#extractText} so it can pass provider+key through to the
 * sidecar's {@code /extract} call. Never logged or returned from a
 * controller (the plaintext key lives here only as long as one request).
 */
public record ActiveOcrConfig(OcrProvider provider, String apiKey) {
}
