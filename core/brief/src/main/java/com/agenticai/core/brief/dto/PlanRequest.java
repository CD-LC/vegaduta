package com.agenticai.core.brief.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * {@code POST /api/briefs/plan} body. {@code locale} (ISO 639-1/2) is the language the narrative
 * is written in - omitted means "detect from the prompt". {@code context} is optional free-form
 * UI state (for example the tour step the user came from); it is treated as data and truncated.
 * {@code depth} is a {@code ReasoningDepth} name (FAST default) applied to workflow-graph
 * generation, the one planner call that has deliberation passes.
 */
public record PlanRequest(
    @NotBlank @Size(max = 4000) String prompt,
    @Size(max = 16) String locale,
    Map<String, Object> context,
    @Size(max = 16) String depth
) {
}
