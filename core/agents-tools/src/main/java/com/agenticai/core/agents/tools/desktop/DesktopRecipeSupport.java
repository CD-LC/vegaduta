package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agents.tools.desktop.DesktopRecipeDtos.RecipeSummary;
import com.agenticai.core.agents.tools.desktop.DesktopRecipeSpec.ParamSpec;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Small pure helpers {@link DesktopRecipeService} shares with the rest of the desktop-recipe
 * surface: caller identity, input cleaning and the entity-to-summary mapping. Package-private on
 * purpose - nothing here is part of the feature's API, the way {@code BrowserStorageState}'s
 * domain helpers are not part of the browser feature's.
 */
final class DesktopRecipeSupport {

    private static final ObjectMapper MAPPER = JsonMappers.lenient();

    static final String PERM_MANAGE_AGENTS = "PERM_MANAGE_AGENTS";
    static final String ROLE_PLATFORM_ADMIN = "ROLE_platform-admin";
    static final int MAX_NAME_LENGTH = 120;
    static final int MAX_DESCRIPTION_LENGTH = 2_000;

    private DesktopRecipeSupport() {}

    static String subject() {
        return TenantContext.current().map(TenantContext::getSubject).orElse(null);
    }

    static boolean isManager() {
        return TenantContext.current()
                .map(ctx -> ctx.hasRole(PERM_MANAGE_AGENTS) || ctx.hasRole(ROLE_PLATFORM_ADMIN))
                .orElse(false);
    }

    static String cleanName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("a recipe name is required");
        }
        String name = raw.strip();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "a recipe name is at most " + MAX_NAME_LENGTH + " characters");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException(
                        "a recipe name must not contain control characters");
            }
        }
        return name;
    }

    static String cleanDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String description = raw.strip();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "a recipe description is at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }
        return description;
    }

    /**
     * {@code AUTO} or {@code MONITOR} (case-insensitive input, stored uppercase); null/blank is
     * AUTO, anything else is rejected.
     */
    static String cleanMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DesktopRecipe.MODE_AUTO;
        }
        String mode = raw.strip().toUpperCase(Locale.ROOT);
        if (!DesktopRecipe.MODE_AUTO.equals(mode) && !DesktopRecipe.MODE_MONITOR.equals(mode)) {
            throw new IllegalArgumentException(
                    "defaultMode must be AUTO or MONITOR, not '" + raw.strip() + "'");
        }
        return mode;
    }

    /**
     * The summary a list or detail endpoint returns: metadata plus the recipe's parameter NAMES
     * (never values). A row whose stored JSON cannot be parsed still renders a summary - the
     * replay path re-validates and refuses it there.
     */
    static RecipeSummary summary(DesktopRecipe r) {
        List<String> paramNames = new ArrayList<>();
        try {
            for (ParamSpec p :
                    MAPPER.readValue(r.getRecipeJson(), DesktopRecipeSpec.class).params()) {
                paramNames.add(p.name());
            }
        } catch (Exception e) {
            // see javadoc
        }
        return new RecipeSummary(
                r.getId(),
                r.getAgentId(),
                r.getName(),
                r.getDescription(),
                r.getAllowedAppList(),
                paramNames,
                r.getStepCount(),
                r.getRunCount(),
                r.getFailCount(),
                r.getLastRunAt(),
                r.getLastFailure(),
                r.getDefaultMode(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
