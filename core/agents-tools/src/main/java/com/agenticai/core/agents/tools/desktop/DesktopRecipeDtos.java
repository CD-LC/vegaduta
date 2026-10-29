package com.agenticai.core.agents.tools.desktop;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response shapes for {@code /api/desktop/recipes}. Nothing here can carry a secret: a
 * recipe is described by its placeholder-only steps and its metadata only.
 */
public final class DesktopRecipeDtos {

    private DesktopRecipeDtos() {}

    /**
     * Create a recipe by hand (or from a previously exported one). The spec is validated exactly
     * like a recorded one - a secret may appear only as a {@code {{secret.NAME}}} inside a type
     * step's text.
     *
     * @param defaultMode {@code AUTO} (default) or {@code MONITOR} - MONITOR pauses between
     *     replayed steps so the person can watch
     */
    public record CreateRecipeRequest(
            UUID agentId,
            String name,
            String description,
            String defaultMode,
            DesktopRecipeSpec spec) {}

    /**
     * Save a finished desktop run as a recipe.
     *
     * @param agentId the agent that ran the task
     * @param sessionId the chat/run session id the desktop tools ran under
     * @param parameters parameter name to the VALUE the user supplied in this run; each occurrence
     *     of the value in a label, target or typed text becomes {@code {{name}}} and the value
     *     itself is discarded
     * @param allowLiteralText when false (default) any typed text that is neither a parameter nor a
     *     placeholder rejects the save instead of storing a literal
     * @param defaultMode {@code AUTO} (default) or {@code MONITOR}
     */
    public record RecipeFromRunRequest(
            UUID agentId,
            String sessionId,
            String name,
            String description,
            Map<String, String> parameters,
            Boolean allowLiteralText,
            String defaultMode) {}

    public record UpdateRecipeRequest(String name, String description, String defaultMode) {}

    /**
     * Replay a recipe over REST.
     *
     * @param confirmIrreversible true only after the user approved the steps that submit or commit
     *     something
     * @param fromStep resume at this 1-based step (after a confirmation pause); default 1
     * @param mode {@code AUTO} or {@code MONITOR}; null uses the recipe's default mode
     */
    public record RunRecipeRequest(
            Map<String, String> params,
            Boolean confirmIrreversible,
            Integer fromStep,
            String mode) {}

    public record RecipeSummary(
            UUID id,
            UUID agentId,
            String name,
            String description,
            List<String> allowedApps,
            List<String> parameters,
            int stepCount,
            int runCount,
            int failCount,
            Instant lastRunAt,
            String lastFailure,
            String defaultMode,
            Instant createdAt,
            Instant updatedAt) {}

    /** A summary plus the placeholder-only steps (safe to show: it never contains a secret). */
    public record RecipeDetail(
            RecipeSummary summary, DesktopRecipeSpec spec, List<String> warnings) {}

    /**
     * The replay report for {@code POST /api/desktop/recipes/{id}/run}: step numbers, action names
     * and a scrubbed failure reason - never a resolved secret.
     */
    public record RunRecipeResponse(
            UUID recipeId,
            String name,
            boolean success,
            int stepsRun,
            int stepsTotal,
            String failure,
            List<DesktopRecipeReplayer.StepResult> steps) {}
}
