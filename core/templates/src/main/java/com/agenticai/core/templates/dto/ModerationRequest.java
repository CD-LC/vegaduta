package com.agenticai.core.templates.dto;

import jakarta.validation.constraints.Size;

/**
 * Reason body for a reject or an admin withdraw. {@code reason} is required for
 * a reject (enforced at the controller), optional for a withdraw.
 */
public record ModerationRequest(
    @Size(max = 2000) String reason
) {
}
