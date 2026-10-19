package com.agenticai.core.platform;

import com.agenticai.core.providers.ProviderName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Reference config for the weekly translation refresh, PLUS (as of #423) a
 * real on-demand trigger: {@link TranslationRunService#triggerNow} dispatches
 * the existing GitHub Actions workflow at {@code
 * .github/workflows/translate-messages.yml} via the GitHub REST API's
 * {@code workflow_dispatch} event, using this row's {@link #lastTriggeredAt}/
 * {@link #lastTriggeredBy}/{@link #lastTriggerStatus}/{@link
 * #lastTriggerMessage} fields to record the outcome of the most recent
 * on-demand run for display in the Ecosystem Maintenance console.
 *
 * <p>The weekly {@code cron: "0 6 * * 1"} schedule keeps running
 * independently of this - triggering now doesn't replace it, it just doesn't
 * require waiting up to a week for a newly-added string (e.g. a whole new
 * Indian-language locale) to get translated. The actual LLM call still
 * happens inside {@code web/scripts/translate-messages.mjs}, run by the
 * dispatched GitHub Actions job using ITS OWN repository secrets ({@code
 * TRANSLATE_LLM_API_KEY} etc.) - this Spring Boot app never calls an LLM
 * directly for this, and never reads/writes {@code web/messages/*.json}
 * itself (those files live in a different deployable, the Next.js app, not
 * this JVM process - see {@code TranslationRunService}'s javadoc for the
 * full reasoning).
 *
 * <p>The {@code cronScheduleDisplay}/{@code providerName}/{@code model}/
 * {@code workflowPathHint}/{@code notes} fields below remain exactly what
 * they always were: an admin-maintained reference description of the
 * workflow's real config, not read by the workflow itself and not
 * automatically kept in sync if someone edits the GitHub Action directly.
 *
 * <p>Lives in the {@code control_plane} schema (platform-wide, not
 * per-tenant), singleton row - always {@code id = 1L}, enforced by {@link
 * TranslationJobSettingsService} always querying/creating that fixed id
 * rather than by any extra database constraint.
 */
@Entity
@Table(name = "translation_job_settings", schema = "control_plane")
public class TranslationJobSettings {

    /** Fixed singleton id - see this class's javadoc. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "cron_schedule_display", nullable = false, length = 64)
    private String cronScheduleDisplay;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_name", nullable = false, length = 32)
    private ProviderName providerName;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "workflow_path_hint", nullable = false, length = 255)
    private String workflowPathHint;

    @Column(length = 1000)
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    // On-demand dispatch tracking (#423) - see this class's javadoc.
    @Column(name = "last_triggered_at")
    private Instant lastTriggeredAt;

    @Column(name = "last_triggered_by", length = 255)
    private String lastTriggeredBy;

    @Column(name = "last_trigger_status", length = 32)
    private String lastTriggerStatus;

    @Column(name = "last_trigger_message", length = 1000)
    private String lastTriggerMessage;

    protected TranslationJobSettings() {
        // JPA
    }

    public TranslationJobSettings(
        String cronScheduleDisplay, ProviderName providerName, String model, String workflowPathHint, String notes
    ) {
        this.id = SINGLETON_ID;
        this.cronScheduleDisplay = cronScheduleDisplay;
        this.providerName = providerName;
        this.model = model;
        this.workflowPathHint = workflowPathHint;
        this.notes = notes;
        this.updatedAt = Instant.now();
    }

    /** Null-safe: only overwrites fields the caller actually supplied. */
    public void applyUpdate(
        String cronScheduleDisplay, ProviderName providerName, String model,
        String workflowPathHint, String notes, String updatedBy
    ) {
        if (cronScheduleDisplay != null) {
            this.cronScheduleDisplay = cronScheduleDisplay;
        }
        if (providerName != null) {
            this.providerName = providerName;
        }
        if (model != null) {
            this.model = model;
        }
        if (workflowPathHint != null) {
            this.workflowPathHint = workflowPathHint;
        }
        if (notes != null) {
            this.notes = notes;
        }
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getCronScheduleDisplay() {
        return cronScheduleDisplay;
    }

    public ProviderName getProviderName() {
        return providerName;
    }

    public String getModel() {
        return model;
    }

    public String getWorkflowPathHint() {
        return workflowPathHint;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getLastTriggeredAt() {
        return lastTriggeredAt;
    }

    public String getLastTriggeredBy() {
        return lastTriggeredBy;
    }

    public String getLastTriggerStatus() {
        return lastTriggerStatus;
    }

    public String getLastTriggerMessage() {
        return lastTriggerMessage;
    }

    /** Called by {@link TranslationRunService#triggerNow} after every dispatch attempt, success or failure. */
    public void recordTrigger(String triggeredBy, String status, String message) {
        this.lastTriggeredAt = Instant.now();
        this.lastTriggeredBy = triggeredBy;
        this.lastTriggerStatus = status;
        this.lastTriggerMessage = message;
    }
}
