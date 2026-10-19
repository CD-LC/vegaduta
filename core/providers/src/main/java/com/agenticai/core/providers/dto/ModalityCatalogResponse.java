package com.agenticai.core.providers.dto;

import java.util.List;

/**
 * Response of {@code GET /api/providers/modalities} - the full STT/TTS/video
 * option catalog for the calling tenant, availability resolved server-side.
 * The workbench renders these lists verbatim (plus a client-side "platform
 * default" row for the clear-selection case); it no longer keeps per-provider
 * arrays or availability probes.
 */
public record ModalityCatalogResponse(
    List<ModalityProviderOption> speechToText,
    List<ModalityProviderOption> textToSpeech,
    List<ModalityProviderOption> videoToText
) {
}
