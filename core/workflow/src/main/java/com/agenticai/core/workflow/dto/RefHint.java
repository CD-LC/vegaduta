package com.agenticai.core.workflow.dto;

/**
 * One planner-side reference handed to {@code WorkflowGraphGeneratorService#generateWithRefs}:
 * a short ref ({@code a1}, {@code k1}) the generated graph may use in place of a real id,
 * plus a display name and an optional one-line role so the model can pick the right one.
 */
public record RefHint(String ref, String name, String description) {
}
