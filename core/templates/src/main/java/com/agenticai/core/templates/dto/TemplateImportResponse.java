package com.agenticai.core.templates.dto;

import com.agenticai.core.templates.TemplateKind;
import java.util.UUID;

/**
 * Result of importing a template: the id (and kind) of the brand-new agent or
 * workflow created in the caller's own tenant.
 */
public record TemplateImportResponse(
    TemplateKind kind,
    UUID resourceId
) {
}
