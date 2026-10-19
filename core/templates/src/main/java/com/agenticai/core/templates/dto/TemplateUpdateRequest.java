package com.agenticai.core.templates.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Edit the presentation of a DRAFT/REJECTED template the caller owns. Only the
 * human-facing metadata is editable here - {@code config_json} stays the snapshot
 * captured at create time (re-snapshotting from a live source is a separate,
 * deliberate action, not a side effect of a title edit).
 */
public record TemplateUpdateRequest(
    @NotBlank(message = "title is required") @Size(max = 200) String title,
    @Size(max = 4000) String summary,
    @Size(max = 64) String category,
    List<@Size(max = 40) String> tags
) {
}
