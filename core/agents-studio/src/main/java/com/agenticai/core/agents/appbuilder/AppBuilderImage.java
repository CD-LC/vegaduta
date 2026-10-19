package com.agenticai.core.agents.appbuilder;

/**
 * One design-input image attached to {@code POST
 * /api/internal/app-builder/generate-files} — a screenshot, a photo of a
 * hand-drawn wireframe, or a Figma PNG export. {@code dataBase64} is the
 * raw image bytes, base64-encoded; {@code mediaType} is the MIME type
 * ("image/png", "image/jpeg", "image/webp"). Attached to the completion as
 * Spring AI media so vision-capable models can see the design the user is
 * describing; {@link AppBuilderCompletionService} falls back to text-only
 * when the resolved model rejects media.
 */
public record AppBuilderImage(
    String name,
    String mediaType,
    String dataBase64
) {
}
