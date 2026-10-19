package com.agenticai.core.tenancy.dto;

/**
 * One row of {@code GET /api/tenant/ai-model-preferences} - one per known
 * {@code AiModelFeature}, whether or not the tenant has actually configured
 * an override for it ({@code providerName}/{@code model} both {@code null}
 * when not set). {@code featureKey}/{@code featureLabel} let the settings
 * page render every feature slot without hardcoding the catalog client-side.
 */
public record AiModelPreferenceResponse(
    String featureKey,
    String featureLabel,
    String providerName,
    String model
) {
}
