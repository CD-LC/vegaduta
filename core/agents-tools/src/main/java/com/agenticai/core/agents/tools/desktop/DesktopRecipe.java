package com.agenticai.core.agents.tools.desktop;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One row of {@code desktop_recipe} (V672): a replayable, placeholder-only desktop recipe. {@link
 * #getRecipeJson()} is a serialized {@link DesktopRecipeSpec}; it never holds a secret or a
 * user-supplied value (enforced by {@link DesktopRecipeValidator} on every write and again before
 * every replay). {@code agentId} null means every agent of the tenant may run it. {@code
 * defaultMode} is the replay posture: {@code AUTO} runs steps back-to-back, {@code MONITOR} pauses
 * between them so the person can watch.
 */
@Entity
@Table(name = "desktop_recipe")
public class DesktopRecipe {

    private static final int MAX_FAILURE_LENGTH = 500;

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_MONITOR = "MONITOR";

    @Id private UUID id;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "allowed_apps", nullable = false, columnDefinition = "TEXT")
    private String allowedApps;

    @Column(name = "default_mode", nullable = false, length = 16)
    private String defaultMode;

    @Column(name = "recipe_json", nullable = false, columnDefinition = "TEXT")
    private String recipeJson;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Column(name = "run_count", nullable = false)
    private int runCount;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_failure", length = 500)
    private String lastFailure;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DesktopRecipe() {
        // JPA
    }

    DesktopRecipe(
            UUID agentId,
            String name,
            String description,
            List<String> allowedApps,
            String defaultMode,
            String recipeJson,
            int stepCount,
            String createdBy,
            Instant now) {
        this.id = UUID.randomUUID();
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.allowedApps = joinApps(allowedApps);
        this.defaultMode = defaultMode == null ? MODE_AUTO : defaultMode;
        this.recipeJson = recipeJson;
        this.stepCount = stepCount;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAgentId() {
        return agentId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getAllowedAppList() {
        return splitApps(allowedApps);
    }

    public String getDefaultMode() {
        return defaultMode;
    }

    public String getRecipeJson() {
        return recipeJson;
    }

    public int getStepCount() {
        return stepCount;
    }

    public int getRunCount() {
        return runCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public String getLastFailure() {
        return lastFailure;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    void edit(String newName, String newDescription, String newDefaultMode, Instant now) {
        if (newName != null) {
            this.name = newName;
        }
        if (newDescription != null) {
            this.description = newDescription;
        }
        if (newDefaultMode != null) {
            this.defaultMode = newDefaultMode;
        }
        this.updatedAt = now;
    }

    /** Records a replay result: {@code failure} null = success. */
    void recordRun(String failure, Instant now) {
        this.runCount++;
        this.lastRunAt = now;
        if (failure == null) {
            this.lastFailure = null;
        } else {
            this.failCount++;
            this.lastFailure =
                    failure.length() <= MAX_FAILURE_LENGTH
                            ? failure
                            : failure.substring(0, MAX_FAILURE_LENGTH);
        }
    }

    /** The CSV form {@code allowed_apps} is stored in (lowercased names). */
    static String joinApps(List<String> apps) {
        if (apps == null) {
            return "";
        }
        List<String> clean = new ArrayList<>();
        for (String app : apps) {
            if (app != null && !app.isBlank()) {
                clean.add(app.strip().toLowerCase(Locale.ROOT));
            }
        }
        return String.join(",", clean);
    }

    /** Inverse of {@link #joinApps}; tolerant of blanks. */
    static List<String> splitApps(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null) {
            return out;
        }
        for (String part : stored.split(",")) {
            String app = part.strip();
            if (!app.isEmpty()) {
                out.add(app);
            }
        }
        return out;
    }
}
