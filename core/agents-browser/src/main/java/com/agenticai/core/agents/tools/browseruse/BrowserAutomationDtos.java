package com.agenticai.core.agents.tools.browseruse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Request/response shapes for {@code /api/browser/profiles}, {@code /api/browser/recipes} and
 * {@code /api/browser/automation}. Nothing here can carry saved login state or a secret: a profile
 * is described by its metadata only (domains, sizes, timestamps), and a recipe by its
 * placeholder-only steps.
 */
public final class BrowserAutomationDtos {

    private BrowserAutomationDtos() {}

    /**
     * Which parts of the feature this deployment has switched on (all default OFF), and whether a
     * headless browser exists at all (with the reason
     * when it does not, so the console can offer the browser add-on).
     */
    public record Capabilities(
            boolean profilesEnabled,
            boolean recipesEnabled,
            boolean totpEnabled,
            boolean browserAvailable,
            String browserUnavailableReason) {}

    // ---- profiles -----------------------------------------------------------

    public record CreateProfileRequest(
            String name, List<String> allowedDomains, String scope, Integer ttlDays) {}

    public record UpdateProfileRequest(String name, List<String> allowedDomains, Integer ttlDays) {}

    /**
     * Metadata only - deliberately no field for the saved cookies/localStorage, in any form.
     *
     * @param ownedByMe true for a USER-scoped profile the caller owns (the owner's subject is never
     *     returned)
     */
    public record ProfileView(
            UUID id,
            String name,
            String scope,
            boolean ownedByMe,
            List<String> allowedDomains,
            boolean hasState,
            int stateBytes,
            int stateEntries,
            int ttlDays,
            Instant expiresAt,
            boolean expired,
            Instant lastSavedAt,
            Instant lastUsedAt,
            Instant createdAt,
            List<UUID> attachedAgentIds) {}

    // ---- recipes ------------------------------------------------------------

    /**
     * Create a recipe by hand (or from a previously exported one). The spec is validated exactly
     * like a recorded one - a typed value in a sensitive field must be a placeholder.
     */
    public record CreateRecipeRequest(
            UUID agentId, String name, String description, BrowserRecipeSpec spec) {}

    /**
     * Save a finished browser run as a recipe.
     *
     * @param agentId the agent that ran the task
     * @param sessionId the chat/run session id the browser tools ran under
     * @param parameters parameter name to the VALUE the user supplied in this run; each occurrence
     *     of the value in a typed text or URL becomes {@code {{name}}} and the value itself is
     *     discarded
     * @param allowLiteralText when false (default) any typed text that is neither a parameter nor a
     *     placeholder rejects the save instead of storing a literal
     */
    public record RecipeFromRunRequest(
            UUID agentId,
            String sessionId,
            String name,
            String description,
            Map<String, String> parameters,
            Boolean allowLiteralText) {}

    public record UpdateRecipeRequest(String name, String description) {}

    public record RecipeSummary(
            UUID id,
            UUID agentId,
            String name,
            String description,
            List<String> allowedDomains,
            List<String> parameters,
            int stepCount,
            int runCount,
            int failCount,
            Instant lastRunAt,
            String lastFailure,
            Instant createdAt,
            Instant updatedAt) {}

    /** A summary plus the placeholder-only steps (safe to show: it never contains a secret). */
    public record RecipeDetail(
            RecipeSummary summary, BrowserRecipeSpec spec, List<String> warnings) {}
}
