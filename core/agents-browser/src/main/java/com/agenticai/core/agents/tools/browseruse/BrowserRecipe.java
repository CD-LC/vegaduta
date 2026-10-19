package com.agenticai.core.agents.tools.browseruse;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of {@code browser_recipe} (V444): a replayable, placeholder-only browser recipe. {@link
 * #getRecipeJson()} is a serialized {@link BrowserRecipeSpec}; it never holds a secret or a
 * user-supplied value (enforced by {@link BrowserRecipeValidator} on every write and again before
 * every replay). {@code agentId} null means every agent of the tenant may run it.
 */
@Entity
@Table(name = "browser_recipe")
public class BrowserRecipe {

    private static final int MAX_FAILURE_LENGTH = 500;

    @Id private UUID id;

    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "allowed_domains", nullable = false, columnDefinition = "TEXT")
    private String allowedDomains;

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

    protected BrowserRecipe() {
        // JPA
    }

    BrowserRecipe(
            UUID agentId,
            String name,
            String description,
            List<String> allowedDomains,
            String recipeJson,
            int stepCount,
            String createdBy,
            Instant now) {
        this.id = UUID.randomUUID();
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.allowedDomains = BrowserStorageState.joinDomains(allowedDomains);
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

    public List<String> getAllowedDomainList() {
        return BrowserStorageState.splitDomains(allowedDomains);
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

    void edit(String newName, String newDescription, Instant now) {
        if (newName != null) {
            this.name = newName;
        }
        if (newDescription != null) {
            this.description = newDescription;
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
}
