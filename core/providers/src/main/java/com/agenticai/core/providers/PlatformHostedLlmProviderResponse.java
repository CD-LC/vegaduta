package com.agenticai.core.providers;

import java.time.Instant;

/**
 * Outbound view of one {@link PlatformHostedLlmProvider} row - deliberately
 * has no field for the API key, encrypted or otherwise, same "there is no
 * way to leak it through this type" posture as {@code
 * com.agenticai.core.providers.dto.ProviderResponse} and {@code
 * com.agenticai.core.platform.PlatformLlmTaskConfigResponse}.
 */
public record PlatformHostedLlmProviderResponse(
    Long id,
    String label,
    String baseUrl,
    String model,
    boolean enabled,
    int priority,
    // #971 - nullable admin-facing region label, see PlatformHostedLlmProvider's javadoc.
    String region,
    // Nullable custom OpenAI-compat completions path (non-null only for the
    // likes of Gemini) - echoed back so an admin sees the stored shape.
    String completionsPath,
    Instant createdAt,
    Instant updatedAt,
    Instant lastValidatedAt,
    // Nullable - the ProviderName this row was configured from, if any (see
    // PlatformHostedLlmProviderUpsertRequest's javadoc). Plain text, not the
    // enum itself, so an unrecognized/retired value never fails to deserialize.
    String providerName,
    // True when `model` was auto-selected rather than hand-typed - see
    // PlatformHostedLlmProvider#modelAutoSelected.
    boolean modelAutoSelected,
    // V169 - one of PlatformHostedLlmAuthType's constants, never null (see
    // that field's javadoc - normalized to API_KEY at the entity layer).
    String authType,
    // V169 - nullable, only meaningful when authType is a Vertex type. Safe
    // to echo back: a GCP project id/location are not secrets.
    String vertexProjectId,
    String vertexLocation,
    // V484 - see PlatformHostedLlmProvider#isZeroCost.
    boolean zeroCost,
    // V499 - STRUCTURED | TEXT | NONE, or null when not measured (see ToolCallGrade).
    String toolCallGrade,
    // V500 - FORMAT | REQUIRED, or null when the server kind is unknown (see ToolRouterMode).
    String toolRouterMode,
    // V500 - the admin override (null = derived from the grade) ...
    Boolean toolRouterEnabled,
    // ... and what it resolves to: the mode chat turns on this row actually use, or null.
    String toolRouterActive,
    // V501 - size as the server reports it (null = unknown), the admin override (null = derived)
    // and what small-model mode resolves to for this row.
    Double parameterBillions,
    Boolean smallModel,
    boolean smallModelActive
) {

    public static PlatformHostedLlmProviderResponse from(PlatformHostedLlmProvider entity) {
        return new PlatformHostedLlmProviderResponse(
            entity.getId(),
            entity.getLabel(),
            entity.getBaseUrl(),
            entity.getModel(),
            entity.isEnabled(),
            entity.getPriority(),
            entity.getRegion(),
            entity.getCompletionsPath(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getLastValidatedAt(),
            entity.getProviderName(),
            entity.isModelAutoSelected(),
            entity.getAuthType(),
            entity.getVertexProjectId(),
            entity.getVertexLocation(),
            entity.isZeroCost(),
            entity.getToolCallGrade() == null ? null : entity.getToolCallGrade().name(),
            entity.getToolRouterMode() == null ? null : entity.getToolRouterMode().name(),
            entity.getToolRouterEnabled(),
            entity.effectiveToolRouterMode() == null ? null : entity.effectiveToolRouterMode().name(),
            entity.getParameterBillions(),
            entity.getSmallModel(),
            entity.effectiveSmallModel()
        );
    }
}
