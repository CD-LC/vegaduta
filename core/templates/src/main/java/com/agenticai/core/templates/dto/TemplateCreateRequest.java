package com.agenticai.core.templates.dto;

import com.agenticai.core.templates.TemplateKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Create a DRAFT template from one of the caller's own agents/workflows. The
 * client supplies only the presentation metadata and WHICH source resource to
 * snapshot ({@code sourceId} + {@code kind}) - it never supplies {@code config_json};
 * the server builds that by an allow-list snapshot of the source entity. See
 * {@code TemplateSnapshotService}.
 */
public record TemplateCreateRequest(
    @NotNull(message = "kind is required") TemplateKind kind,
    @NotNull(message = "sourceId is required") UUID sourceId,
    @NotBlank(message = "title is required") @Size(max = 200) String title,
    @Size(max = 4000) String summary,
    @Size(max = 64) String category,
    List<@Size(max = 40) String> tags
) {
}
