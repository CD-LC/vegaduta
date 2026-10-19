package com.agenticai.core.templates.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Member rating write: a required 1-5 star score plus an optional review.
 * Upsert semantics - submitting again replaces the member's previous rating.
 */
public record TemplateRatingRequest(
    @NotNull @Min(1) @Max(5) Integer stars,
    @Size(max = 2000) String review
) {
}
